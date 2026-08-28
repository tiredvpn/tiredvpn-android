package com.tiredvpn.android.importer

import android.content.Context
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.SplitTunnelSettings
import com.tiredvpn.android.vpn.VpnConfig

/**
 * Decides what a parsed payload would do to the stored server list, then does it.
 *
 * Planning is separated from applying so the preview the user confirms and the
 * write that follows are produced by the same code. A preview computed by one
 * routine and a save performed by another is how "it said 4, it stored 1" bugs
 * happen.
 */
object ConfigImporter {

    enum class Action {
        /** No stored server matches; this one gets appended. */
        ADD,

        /** A stored server matches; its credentials and options are replaced. */
        UPDATE,

        /** An earlier entry in the same payload already claimed this endpoint. */
        DUPLICATE,
    }

    data class PlannedEntry(
        val config: VpnConfig,
        val action: Action,
        val splitTunnel: ConfigCodec.SplitTunnelSpec? = null,
    ) {
        /** Endpoint and name only - never the secret; this string reaches the UI. */
        val label: String get() = "${config.name} (${config.serverAddress}:${config.serverPort})"
    }

    data class Plan(
        val entries: List<PlannedEntry>,
        val skipped: List<ConfigCodec.Skipped>,
    ) {
        val toAdd: List<PlannedEntry> get() = entries.filter { it.action == Action.ADD }
        val toUpdate: List<PlannedEntry> get() = entries.filter { it.action == Action.UPDATE }
        val duplicates: List<PlannedEntry> get() = entries.filter { it.action == Action.DUPLICATE }

        /** Entries that will actually be written. */
        val writable: List<PlannedEntry>
            get() = entries.filter { it.action != Action.DUPLICATE }

        val hasWork: Boolean get() = writable.isNotEmpty()
    }

    data class Result(val added: Int, val updated: Int, val skipped: Int)

    const val REASON_DUPLICATE = "already listed earlier in this import"

    /**
     * Two payload entries describe the same server when they dial the same
     * place. Not the name (users rename), not the id (a tired:// link carries no
     * id, so every parse of the same link mints a fresh one - keying on it is
     * exactly what made re-importing a link create a second entry).
     *
     * Since core 1.8.0 each pool node has its own secret, so a changed secret on
     * a known endpoint is a rotated credential, not a different server: update.
     */
    fun dedupKey(config: VpnConfig): String {
        // An IPv6 literal reaches serverAddress with brackets when Uri parsed the
        // link and without them when the manual authority fallback did. Same host.
        val host = config.serverAddress.trim().lowercase().trim('[', ']')
        return "$host:${config.serverPort}"
    }

    fun plan(existing: List<VpnConfig>, parsed: ConfigCodec.ParseResult): Plan {
        val byId = existing.associateBy { it.id }
        val byEndpoint = existing.associateBy { dedupKey(it) }

        val entries = mutableListOf<PlannedEntry>()
        val skipped = parsed.skipped.toMutableList()
        val claimed = mutableSetOf<String>()

        for (server in parsed.servers) {
            val incoming = server.config
            val key = dedupKey(incoming)

            if (!claimed.add(key)) {
                entries += PlannedEntry(incoming, Action.DUPLICATE, server.splitTunnel)
                skipped += ConfigCodec.Skipped("${incoming.serverAddress}:${incoming.serverPort}", REASON_DUPLICATE)
                continue
            }

            // An id match comes first: a backup file round-trips by id, which
            // survives the user moving a server to a different address.
            val match = byId[incoming.id] ?: byEndpoint[key]
            if (match == null) {
                entries += PlannedEntry(incoming, Action.ADD, server.splitTunnel)
            } else {
                entries += PlannedEntry(merge(match, incoming), Action.UPDATE, server.splitTunnel)
            }
        }
        return Plan(entries, skipped)
    }

    /**
     * Incoming values win, with two exceptions that are properties of this device
     * rather than of the server:
     *  - the stored id, so split-tunnel rules and the active-server pointer keep
     *    pointing at the same profile;
     *  - the measured latency, which the sender cannot know.
     *
     * A name the sender did not choose (a bare link names the server after its
     * host) must not overwrite a name the user did choose.
     */
    private fun merge(existing: VpnConfig, incoming: VpnConfig): VpnConfig {
        val incomingNameIsAuto = incoming.name.isBlank() ||
            incoming.name == "Server" ||
            incoming.name.equals(incoming.serverAddress, ignoreCase = true)
        return incoming.copy(
            id = existing.id,
            lastLatencyMs = existing.lastLatencyMs,
            name = if (incomingNameIsAuto) existing.name else incoming.name,
        )
    }

    /** Plan against what is currently stored. */
    fun plan(context: Context, parsed: ConfigCodec.ParseResult): Plan =
        plan(ServerRepository.getServers(context), parsed)

    /**
     * Write a plan. Returns the counts the user is shown; they are derived from
     * the same list that was written, not recounted from the payload.
     */
    fun apply(context: Context, plan: Plan): Result {
        val hadActiveServer = ServerRepository.getActiveServer(context) != null
        val writable = plan.writable

        for (entry in writable) {
            ServerRepository.saveServer(context, entry.config)
            entry.splitTunnel?.let {
                SplitTunnelSettings.save(context, entry.config.id, it.mode, it.apps)
            }
        }

        // Selecting an active server is only obvious when there is one candidate.
        // Importing a pool of four must not silently move the user onto whichever
        // node happened to be last in the file.
        val single = writable.singleOrNull()
        if (single != null && (!hadActiveServer || single.action == Action.ADD)) {
            ServerRepository.setActiveServerId(context, single.config.id)
        }

        return Result(
            added = plan.toAdd.size,
            updated = plan.toUpdate.size,
            skipped = plan.skipped.size,
        )
    }

    /** Parse, plan and write in one step. For non-interactive callers only. */
    fun importDirect(context: Context, raw: String?): Result {
        val parsed = ConfigCodec.parse(raw)
        return apply(context, plan(context, parsed))
    }
}
