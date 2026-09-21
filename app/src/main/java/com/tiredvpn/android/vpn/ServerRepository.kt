package com.tiredvpn.android.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tiredvpn.android.util.FileLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where server profiles live.
 *
 * Everything public here is serialized on one monitor. The reason is the
 * traffic pattern, not theory: opening the server list fires one coroutine per
 * server to ping it and each one wrote the whole list back, while the import
 * screen, twenty settings toggles, the VPN service, three receivers and a
 * Worker all touched the same key. Read-modify-write on SharedPreferences
 * under that load loses updates as a matter of course, and a delete racing a
 * wave of pings brought the deleted profile back.
 *
 * [updateLatency] exists so the common writer stops rewriting the whole list
 * for one number.
 */
/**
 * The decision behind [ServerRepository.updateLatency], with no Context in it.
 *
 * Extracted so the rule that matters can be tested: a probe that finishes
 * after its server was deleted must write nothing. The old ping wave called
 * `saveServer` with a whole list captured before the delete, so the next probe
 * to land put the removed server back.
 */
internal object LatencyUpdate {

    /**
     * @return the list to store, or null when nothing should be written —
     *         either the server is gone or the value has not changed.
     */
    fun apply(servers: List<VpnConfig>, id: String, latencyMs: Long): List<VpnConfig>? {
        val index = servers.indexOfFirst { it.id == id }
        if (index < 0) return null
        if (servers[index].lastLatencyMs == latencyMs) return null
        return servers.toMutableList().also {
            it[index] = it[index].copy(lastLatencyMs = latencyMs)
        }
    }
}

object ServerRepository {
    private const val TAG = "ServerRepository"

    private const val PREFS_NAME = "tiredvpn_servers"
    private const val PREFS_NAME_ENCRYPTED = "tiredvpn_servers_enc"
    private const val KEY_SERVERS = "servers"
    private const val KEY_ACTIVE_SERVER_ID = "active_server_id"

    // What was changed while the encrypted store was unavailable. Kept in the
    // plaintext store next to the data they describe, so they are cleared by
    // the same edit that clears it. See StoreReconciliation.
    private const val KEY_DEGRADED_DIRTY = "degraded_dirty_ids"
    private const val KEY_DEGRADED_DELETED = "degraded_deleted_ids"
    private const val KEY_DEGRADED_ACTIVE = "degraded_active_id_chosen"

    /**
     * Sticky: the journal was found unreadable at least once.
     *
     * Kept separate from the sets it describes because it has to survive the
     * thing that damaged them. While it stands, the fold refuses — we know
     * something was changed under a failed Keystore and cannot say what, and
     * guessing costs a server profile.
     */
    private const val KEY_DEGRADED_JOURNAL_DAMAGED = "degraded_journal_damaged"

    // Legacy prefs for migration
    private const val LEGACY_PREFS_NAME = "tiredvpn_config"

    /** Serializes every read-modify-write. Reentrant, so helpers may nest. */
    private val lock = Any()

    /**
     * The encrypted store, built once.
     *
     * Each `getPrefs()` used to construct a MasterKey and an
     * EncryptedSharedPreferences from scratch — Keystore work — and
     * `getActiveServer` alone did it three times, from the main thread, dozens
     * of times per screen.
     */
    @Volatile
    private var cachedEncrypted: SharedPreferences? = null

    /**
     * True when secrets are being written to a plaintext store because the
     * encrypted one could not be opened.
     *
     * Deliberately visible, because the alternative was worse in both
     * directions. Refusing to fall back turns a Keystore hiccup into "all your
     * servers are gone" for a tool people rely on to reach the network at all;
     * falling back silently, which is what the old code did, puts every server
     * secret in cleartext with nothing to show for it. So: keep working, keep
     * the data, and make the degradation loud and readable — the UI can warn,
     * and [storageDegradationReason] says what failed.
     *
     * The fallback instance is never cached, so a transient failure heals on
     * the next call and the plaintext copy is folded back into the encrypted
     * store by [reconcileStoresLocked].
     */
    @Volatile
    var isStorageDegraded: Boolean = false
        private set

    @Volatile
    var storageDegradationReason: String? = null
        private set

    /** True when the last load found a payload that was not a closed JSON array. */
    @Volatile
    var lastLoadWasCorrupt: Boolean = false
        private set

    /** How many individual entries the last load had to skip. */
    @Volatile
    var lastLoadSkippedEntries: Int = 0
        private set

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME_ENCRYPTED,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** The encrypted store, or null when it cannot be opened right now. */
    private fun encryptedPrefsOrNull(context: Context): SharedPreferences? {
        cachedEncrypted?.let { return it }
        return try {
            val prefs = getEncryptedPrefs(context)
            cachedEncrypted = prefs
            if (isStorageDegraded) {
                FileLogger.i(TAG, "Encrypted storage is available again, leaving degraded mode")
            }
            isStorageDegraded = false
            storageDegradationReason = null
            prefs
        } catch (e: Throwable) {
            // Throwable and not Exception: androidx.security is an alpha
            // artifact and its Keystore failures surface as Errors often
            // enough that catching only Exception leaves the app dead.
            val reason = e.message ?: e.javaClass.simpleName
            if (!isStorageDegraded || storageDegradationReason != reason) {
                FileLogger.e(TAG, "=== ENCRYPTED STORAGE UNAVAILABLE: server secrets are being kept in plaintext ===", e)
            }
            isStorageDegraded = true
            storageDegradationReason = reason
            null
        }
    }

    private fun plainPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getPrefs(context: Context): SharedPreferences =
        encryptedPrefsOrNull(context) ?: plainPrefs(context)

    fun getServers(context: Context): List<VpnConfig> = synchronized(lock) {
        reconcileStoresLocked(context)
        loadServersLocked(context).servers
    }

    fun getServer(context: Context, id: String): VpnConfig? =
        getServers(context).find { it.id == id }

    /**
     * @return false when the store refused the write. The caller is the only
     *         one that can tell the user; this used to return Unit and log, so
     *         a failed save looked exactly like a successful one on screen.
     */
    fun saveServer(context: Context, config: VpnConfig): Boolean = synchronized(lock) {
        reconcileStoresLocked(context)
        saveServerLocked(context, config)
    }

    /**
     * Write one server's measured latency without rewriting the list.
     *
     * The ping wave used [saveServer], so N concurrent probes each read the
     * whole list, changed one entry and wrote all of it back; the last writer
     * won and every other measurement vanished, and a delete that landed
     * mid-wave was undone by the next probe's stale copy.
     *
     * @return true when a server with [id] existed and its value changed.
     */
    fun updateLatency(context: Context, id: String, latencyMs: Long): Boolean = synchronized(lock) {
        reconcileStoresLocked(context)
        val updated = LatencyUpdate.apply(loadServersLocked(context).servers, id, latencyMs)
        if (updated == null) {
            // Either nothing changed, or the server was deleted while the probe
            // was in flight. Writing here is exactly how a removed server used
            // to reappear.
            FileLogger.d(TAG, "updateLatency: nothing to write for $id")
            return false
        }
        // false here means "nothing was written", which is what the caller
        // asked about — whether because nothing changed or because the store
        // refused it.
        saveServersLocked(context, updated)
    }

    /** @return false when the store refused the write; the server is still there. */
    fun deleteServer(context: Context, id: String): Boolean = synchronized(lock) {
        reconcileStoresLocked(context)
        val servers = loadServersLocked(context).servers.toMutableList()
        val wasActive = activeServerIdLocked(context) == id

        servers.removeAll { it.id == id }
        val nextActive = if (wasActive) servers.firstOrNull()?.id else null

        // One transaction: the shortened list, the successor (or the removal of
        // the key), and the journal entry that says this delete happened while
        // the store was degraded. Split across two writes, a crash in between
        // leaves the server gone from plaintext with nothing saying so, and the
        // fold brings it back from the encrypted copy.
        return saveServersLocked(context, servers) { editor ->
            if (wasActive) {
                if (nextActive != null) editor.putString(KEY_ACTIVE_SERVER_ID, nextActive)
                else editor.remove(KEY_ACTIVE_SERVER_ID)
            }
            markServerChanged(context, editor, deleted = id)
        }
    }

    fun getActiveServer(context: Context): VpnConfig? = synchronized(lock) {
        reconcileStoresLocked(context)
        val servers = loadServersLocked(context).servers
        if (servers.isEmpty()) return null

        val activeId = activeServerIdLocked(context)
        servers.find { it.id == activeId } ?: servers.first().also {
            // If active ID not found but servers exist, default to first
            setActiveServerIdLocked(context, it.id)
        }
    }

    /** @return false when the store refused the write; the choice did not land. */
    fun setActiveServerId(context: Context, id: String): Boolean = synchronized(lock) {
        reconcileStoresLocked(context)
        // The choice and the note that it was made while degraded, together.
        val editor = getPrefs(context).edit().putString(KEY_ACTIVE_SERVER_ID, id)
        markActiveChoice(editor)
        if (editor.commit()) return@synchronized true
        FileLogger.e(TAG, "=== ACTIVE SERVER WRITE FAILED === the store refused the transaction")
        false
    }

    // --- internals, all called with [lock] held ---

    /** What one load of the stored list found. */
    internal data class LoadResult(
        val servers: List<VpnConfig>,
        /** Entries that were present but could not be parsed. */
        val skipped: Int,
        /** The payload was not a closed JSON array at all. */
        val corrupt: Boolean,
    )

    /**
     * Parse the stored list entry by entry.
     *
     * The old loader wrapped the whole loop in one try and printed a stack
     * trace: a bad third element out of ten silently produced three servers,
     * and damage at the top level produced an empty list indistinguishable
     * from a fresh install. Structure is checked without org.json (see
     * [JsonArraySplitter]), then each element is parsed on its own, so one bad
     * entry costs one entry.
     */
    private fun loadServersLocked(context: Context): LoadResult {
        val raw = getPrefs(context).getString(KEY_SERVERS, null)

        val result = when (val verdict = ServerStoreIntegrity.classify(raw)) {
            is ServerStoreIntegrity.Verdict.Empty -> LoadResult(emptyList(), 0, corrupt = false)

            is ServerStoreIntegrity.Verdict.Corrupt -> {
                FileLogger.e(TAG, "=== SERVER LIST IS DAMAGED === stored payload is not a JSON array (${raw?.length ?: 0} chars); refusing to report it as 'no servers'")
                LoadResult(emptyList(), 0, corrupt = true)
            }

            is ServerStoreIntegrity.Verdict.Intact -> {
                val servers = mutableListOf<VpnConfig>()
                var skipped = 0
                for (element in verdict.elements) {
                    try {
                        servers.add(VpnConfig.fromJson(JSONObject(element)))
                    } catch (e: Exception) {
                        skipped++
                        FileLogger.e(TAG, "Skipping unreadable server entry: ${e.message}")
                    }
                }
                if (skipped > 0) {
                    FileLogger.e(TAG, "Loaded ${servers.size} server(s), skipped $skipped unreadable entr(ies)")
                }
                LoadResult(servers, skipped, corrupt = false)
            }
        }

        lastLoadWasCorrupt = result.corrupt
        lastLoadSkippedEntries = result.skipped
        return result
    }

    private fun saveServerLocked(context: Context, config: VpnConfig): Boolean {
        val servers = loadServersLocked(context).servers.toMutableList()
        val index = servers.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            servers[index] = config
        } else {
            servers.add(config)
        }

        // If this is the only server, or no active server is set, make it
        // active — decided here so the whole write is one transaction.
        val adoptAsActive =
            if (servers.size == 1 || activeServerIdLocked(context) == null) config.id else null

        return saveServersLocked(context, servers) { editor ->
            if (adoptAsActive != null) editor.putString(KEY_ACTIVE_SERVER_ID, adoptAsActive)
            // Saved after a delete: it exists again, and the delete is history.
            markServerChanged(context, editor, dirty = config.id, undeleted = config.id)
        }
    }

    /**
     * Write the server list, and whatever else belongs to the same change, in
     * one transaction.
     *
     * One [SharedPreferences.Editor] and `commit()`, not a write followed by a
     * second write for the degraded-mode journal. Two `apply()` calls are two
     * enqueued file writes: the process dying between them leaves the new
     * payload on disk with no mark against it, and the fold then reads that
     * record as an old copy and keeps the encrypted one — losing exactly the
     * change the journal exists to protect. A single editor is written to a
     * temp file and renamed, so the payload and its mark land together or not
     * at all.
     */
    private fun saveServersLocked(
        context: Context,
        servers: List<VpnConfig>,
        also: (SharedPreferences.Editor) -> Unit = {},
    ): Boolean {
        val jsonArray = JSONArray()
        servers.forEach { jsonArray.put(it.toJson()) }
        val editor = getPrefs(context).edit().putString(KEY_SERVERS, jsonArray.toString())
        also(editor)
        if (editor.commit()) return true
        FileLogger.e(TAG, "=== SERVER LIST WRITE FAILED === the store refused the transaction")
        return false
    }

    // --- what changed while the Keystore was down ---
    //
    // Only writes that landed in plaintext are recorded, and only writes that
    // represent something the user did. The point is to tell a degraded-mode
    // change apart from a stale copy of the same record, so the fold can let
    // the change win; marking anything else would hand that privilege to data
    // that has not changed.
    //
    // All of these take the caller's [SharedPreferences.Editor] rather than
    // opening their own: the mark and the data it describes have to reach the
    // disk in the same transaction, or the mark is not evidence of anything.

    /**
     * Record a change to one server in [editor].
     *
     * @param dirty the id whose plaintext record is now the newer one.
     * @param deleted the id that must not come back from the encrypted store.
     * @param undeleted the id that exists again, so an earlier delete no longer
     *        describes it.
     */
    private fun markServerChanged(
        context: Context,
        editor: SharedPreferences.Editor,
        dirty: String? = null,
        deleted: String? = null,
        undeleted: String? = null,
    ) {
        if (!isStorageDegraded) return
        val plain = plainPrefs(context)

        // A damaged journal is not an empty one. Overwriting it with a fresh
        // set is how the marks of an earlier degraded spell disappeared: the
        // fold had refused because it could not read them, the Keystore went
        // down again, one save later the unreadable value was replaced by a
        // tidy set containing only that save, and every profile changed during
        // the first spell now looked untouched — and lost its conflict against
        // the encrypted copy.
        //
        // So the unreadable value is left exactly as it is, and the damage is
        // recorded instead. [StoreReconciliation] refuses while that flag
        // stands, which is the honest answer: something changed here and we
        // cannot say what.
        val dirtyIds = readJournalSetOrNull(plain, KEY_DEGRADED_DIRTY)
        val deletedIds = readJournalSetOrNull(plain, KEY_DEGRADED_DELETED)
        if (dirtyIds == null || deletedIds == null) {
            FileLogger.e(TAG, "=== NOT REWRITING A DAMAGED JOURNAL === the marks of an earlier degraded spell are unreadable; recording the damage and leaving them alone")
            editor.putBoolean(KEY_DEGRADED_JOURNAL_DAMAGED, true)
            return
        }

        val nextDirty = dirtyIds.toMutableSet()
        val nextDeleted = deletedIds.toMutableSet()
        if (dirty != null) { nextDirty.add(dirty); nextDeleted.remove(dirty) }
        if (deleted != null) { nextDeleted.add(deleted); nextDirty.remove(deleted) }
        if (undeleted != null) nextDeleted.remove(undeleted)

        editor.putStringSet(KEY_DEGRADED_DIRTY, nextDirty)
            .putStringSet(KEY_DEGRADED_DELETED, nextDeleted)
    }

    /**
     * Record in [editor] that the active server was *chosen* while degraded.
     *
     * Deliberately not called from [setActiveServerIdLocked]: that also runs
     * when [getActiveServer] repairs an id that names nothing and when
     * [saveServerLocked] adopts the first server, neither of which is a choice
     * worth beating the encrypted store with.
     */
    private fun markActiveChoice(editor: SharedPreferences.Editor) {
        if (!isStorageDegraded) return
        editor.putBoolean(KEY_DEGRADED_ACTIVE, true)
    }

    /**
     * One set out of the journal, or null when it cannot be read as one.
     *
     * `getStringSet` throws `ClassCastException` when the key holds something
     * else — a half-written file, an older layout, anything. Thrown from here
     * it would escape a read path that has nothing to do with the journal, so
     * damage is reported as damage and the caller decides.
     */
    private fun readJournalSetOrNull(prefs: SharedPreferences, key: String): Set<String>? =
        try {
            prefs.getStringSet(key, emptySet()).orEmpty()
        } catch (e: ClassCastException) {
            FileLogger.e(TAG, "=== DEGRADED-MODE JOURNAL IS DAMAGED === $key is not a set of ids", e)
            null
        }

    private fun activeServerIdLocked(context: Context): String? =
        getPrefs(context).getString(KEY_ACTIVE_SERVER_ID, null)

    private fun setActiveServerIdLocked(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_SERVER_ID, id).apply()
    }

    private fun clearActiveServerIdLocked(context: Context) {
        getPrefs(context).edit().remove(KEY_ACTIVE_SERVER_ID).apply()
    }

    /**
     * Fold anything left in the plaintext stores into the encrypted one.
     *
     * Two callers in one rule: the original one-off migration off
     * `tiredvpn_servers`, and recovery from a degraded spell, where the writes
     * that happened while the Keystore was unavailable went to plaintext.
     *
     * Called from every public entry point, not only from the two read paths.
     * A `saveServer` or `deleteServer` that runs in a healed window without a
     * `getServers` before it would otherwise read the encrypted store while the
     * plaintext copy still held records, and write back a list those records
     * were missing from.
     */
    private fun reconcileStoresLocked(context: Context) {
        val encrypted = encryptedPrefsOrNull(context) ?: return // still degraded, nothing to fold into
        migratePlaintextToEncryptedLocked(encrypted, plainPrefs(context))
        migrateLegacyConfigLocked(context)
    }

    /**
     * Merge the plaintext copy into [encrypted] and only then destroy it.
     *
     * Internal rather than private so the fold can be tested against two real
     * preference stores. Standing in an ordinary `SharedPreferences` for the
     * encrypted one is the whole trick: [EncryptedSharedPreferences] needs a
     * Keystore, which a unit test does not have, and the defect being guarded
     * against here is about what gets written, not about the cipher.
     *
     * See [StoreReconciliation] for why this is a union and not a copy.
     */
    internal fun migratePlaintextToEncryptedLocked(
        encrypted: SharedPreferences,
        plain: SharedPreferences,
    ) {
        // The journal decides which side of a conflict wins, so an unreadable
        // one is not "no marks" — it is not knowing, and the fold must not
        // guess. Read before anything is written and refuse on damage.
        //
        // The flag is checked first and is sticky: damage found once stands
        // until something can say what was changed. Without it the refusal was
        // only as durable as the damaged value itself, and the next degraded
        // spell replaced that value with a tidy set — after which the marks of
        // the spell before it were simply gone.
        val previouslyDamaged = try {
            plain.getBoolean(KEY_DEGRADED_JOURNAL_DAMAGED, false)
        } catch (e: ClassCastException) {
            true
        }
        val dirtyIds = readJournalSetOrNull(plain, KEY_DEGRADED_DIRTY)
        val deletedIds = readJournalSetOrNull(plain, KEY_DEGRADED_DELETED)
        val activeChosen = try {
            plain.getBoolean(KEY_DEGRADED_ACTIVE, false)
        } catch (e: ClassCastException) {
            FileLogger.e(TAG, "=== DEGRADED-MODE JOURNAL IS DAMAGED === $KEY_DEGRADED_ACTIVE is not a flag", e)
            null
        }
        if (previouslyDamaged || dirtyIds == null || deletedIds == null || activeChosen == null) {
            FileLogger.e(TAG, "=== NOT MIGRATING THE PLAINTEXT SERVER LIST === the degraded-mode journal is unreadable; both copies are kept for recovery")
            // Record it, so the refusal survives the value that caused it.
            if (!previouslyDamaged) {
                plain.edit().putBoolean(KEY_DEGRADED_JOURNAL_DAMAGED, true).apply()
            }
            return
        }

        val plan = StoreReconciliation.plan(
            encryptedPayload = encrypted.getString(KEY_SERVERS, null),
            encryptedActiveId = encrypted.getString(KEY_ACTIVE_SERVER_ID, null),
            plainPayload = plain.getString(KEY_SERVERS, null),
            plainActiveId = plain.getString(KEY_ACTIVE_SERVER_ID, null),
            plainHasList = plain.contains(KEY_SERVERS),
            dirtyIds = dirtyIds,
            deletedIds = deletedIds,
            activeIdChosenWhileDegraded = activeChosen,
        )

        when (plan) {
            is StoreReconciliation.Plan.Nothing -> return

            is StoreReconciliation.Plan.Refuse -> {
                FileLogger.e(TAG, "=== NOT MIGRATING THE PLAINTEXT SERVER LIST === ${plan.reason}; both copies are kept for recovery")
                return
            }

            is StoreReconciliation.Plan.Fold -> {
                try {
                    // commit() and not apply(): the read-back below has to see
                    // the write, and the source is cleared on the strength of
                    // what it finds.
                    val editor = encrypted.edit().putString(KEY_SERVERS, plan.payload)
                    // Never putString(…, null) here — that removes the key. The
                    // active server is set, or left alone, and nothing else.
                    if (plan.activeId != null) editor.putString(KEY_ACTIVE_SERVER_ID, plan.activeId)
                    if (!editor.commit()) {
                        FileLogger.e(TAG, "Migration to encrypted storage was not committed, keeping the plaintext copy")
                        return
                    }

                    val readBack = encrypted.getString(KEY_SERVERS, null)
                    if (!StoreReconciliation.containsAll(readBack, plan.expectedIds)) {
                        FileLogger.e(TAG, "Encrypted storage did not read back the ${plan.expectedIds.size} record(s) just written; keeping the plaintext copy")
                        return
                    }

                    plain.edit().clear().apply()
                    FileLogger.i(TAG, "Folded the plaintext server list into encrypted storage (${plan.expectedIds.size} record(s))")
                } catch (e: Exception) {
                    // Source untouched: a half-done migration must not be able
                    // to lose the data it failed to copy.
                    FileLogger.e(TAG, "Migration to encrypted storage failed, keeping the plaintext copy", e)
                }
            }
        }
    }

    private fun migrateLegacyConfigLocked(context: Context) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (!legacyPrefs.contains("server_address")) return

        val serverAddress = legacyPrefs.getString("server_address", "") ?: ""
        if (serverAddress.isNotBlank()) {
            val config = VpnConfig(
                name = "Default Server",
                serverAddress = serverAddress,
                serverPort = legacyPrefs.getInt("server_port", 993),
                secret = legacyPrefs.getString("secret", "") ?: "",
                strategy = legacyPrefs.getString("strategy", "auto") ?: "auto",
                enableQuic = legacyPrefs.getBoolean("enable_quic", true),
                quicPort = legacyPrefs.getInt("quic_port", 443),
                coverHost = legacyPrefs.getString("cover_host", "api.googleapis.com") ?: "api.googleapis.com",
                rttMasking = legacyPrefs.getBoolean("rtt_masking", false),
                rttProfile = legacyPrefs.getString("rtt_profile", "moscow-yandex") ?: "moscow-yandex",
                fallbackEnabled = legacyPrefs.getBoolean("fallback_enabled", true),
                debugLogging = legacyPrefs.getBoolean("debug_logging", false)
            )
            saveServerLocked(context, config)
        }
        legacyPrefs.edit().clear().apply()
    }
}
