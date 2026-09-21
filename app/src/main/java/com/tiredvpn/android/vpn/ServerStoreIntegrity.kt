package com.tiredvpn.android.vpn

/**
 * Splits a stored JSON array into the raw text of its top-level elements.
 *
 * Written by hand rather than with org.json for the same reason as
 * [ControlSocketProtocol]: org.json comes from the mocked android.jar in unit
 * tests and returns defaults, so anything built on it could not be tested at
 * all. The split also has to survive a payload that org.json would reject
 * wholesale, which is the case this exists for.
 */
internal object JsonArraySplitter {

    /**
     * @return one string per element, in order, or null when [text] is not a
     *         syntactically closed JSON array.
     */
    fun elements(text: String): List<String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null

        val body = trimmed.substring(1, trimmed.length - 1)
        if (body.isBlank()) return emptyList()

        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false

        for (c in body) {
            when {
                escaped -> { current.append(c); escaped = false }
                inString && c == '\\' -> { current.append(c); escaped = true }
                c == '"' -> { current.append(c); inString = !inString }
                inString -> current.append(c)
                c == '{' || c == '[' -> { depth++; current.append(c) }
                c == '}' || c == ']' -> { depth--; current.append(c); if (depth < 0) return null }
                c == ',' && depth == 0 -> { out.add(current.toString()); current.setLength(0) }
                else -> current.append(c)
            }
        }

        if (depth != 0 || inString || escaped) return null
        out.add(current.toString())

        val cleaned = out.map { it.trim() }
        // A trailing or doubled comma leaves an empty slot; that is damage,
        // not an element.
        if (cleaned.any { it.isEmpty() }) return null
        return cleaned
    }
}

/**
 * Tells "the user has no servers" apart from "the server list is damaged".
 *
 * Collapsing the two is what made the old loader dangerous. `loadServersRaw`
 * wrapped the whole parse loop in one try and printed the stack trace: a
 * payload that failed at the top level produced an empty list, and the empty
 * list was then indistinguishable from a fresh install — including to
 * `migratePlaintextToEncrypted`, which cleared the source it had just failed
 * to copy.
 */
internal object ServerStoreIntegrity {

    sealed class Verdict {
        /** Nothing stored, or an empty array. Normal for a fresh install. */
        data object Empty : Verdict()

        /** A closed array; each element still has to be parsed individually. */
        data class Intact(val elements: List<String>) : Verdict()

        /** Not a closed JSON array. The contents must not be treated as "none". */
        data object Corrupt : Verdict()
    }

    fun classify(raw: String?): Verdict {
        if (raw == null || raw.isBlank()) return Verdict.Empty
        val elements = JsonArraySplitter.elements(raw) ?: return Verdict.Corrupt
        return if (elements.isEmpty()) Verdict.Empty else Verdict.Intact(elements)
    }

    /** True when [raw] may be destroyed because a good copy was made from it. */
    fun isSafeToClearSource(raw: String?): Boolean = classify(raw) !is Verdict.Corrupt
}

/**
 * What to do with a plaintext server list that is sitting next to an encrypted
 * one.
 *
 * The plaintext store is a fallback: when the Keystore cannot be opened,
 * [ServerRepository] keeps working by reading and writing plain preferences, and
 * whatever the user does during that spell lands there. Folding that back in
 * used to be a copy — plaintext payload over encrypted payload, plaintext active
 * id over encrypted active id, source cleared — on the theory that the plaintext
 * copy is by definition the newer one.
 *
 * It is not. During a degraded spell the *reads* come from plaintext too, so it
 * starts from whatever was there (usually nothing) rather than from the real
 * list; and a plaintext copy left behind by a Keystore failure on some older
 * build is not newer than anything. The copy therefore replaced a full list with
 * a stale or empty one, and `putString(active_id, null)` removed the active
 * server on the way past. Secrets live in exactly one place, so that was
 * unrecoverable.
 *
 * What this does instead: union by id, with the conflict decided by evidence
 * rather than by a guess. Every server present on either side survives.
 *
 * The guess that had to go was "the encrypted record always wins a conflicting
 * id". It is right for the case this was written for — a plaintext copy left
 * behind by a Keystore failure on some older build, which is simply old — and
 * wrong for the case the fallback exists to serve: a user who changed a
 * server's secret, or chose a different active server, while the Keystore was
 * down. Both look identical in the payload, and restoring a JSON backup makes
 * it worse, because `VpnConfig.fromJson` keeps the id, so a restored record
 * collides with the encrypted one by construction.
 *
 * So the writes made during a degraded spell say so. [ServerRepository] records
 * the id of every server it saved or deleted while writing to plaintext, and
 * whether the active server was chosen there; those records win, and only
 * those. An unmarked plaintext record is an old copy and loses. The active id
 * is only ever set, never cleared.
 */
internal object StoreReconciliation {

    sealed class Plan {
        /** No plaintext list at all. Touch nothing. */
        data object Nothing : Plan()

        /**
         * One of the two payloads is not a closed JSON array. Migrate nothing
         * and — this is the point — clear nothing either.
         */
        data class Refuse(val reason: String) : Plan()

        /**
         * Write [payload] into the encrypted store, confirm it reads back with
         * every id in [expectedIds], and only then clear the plaintext source.
         *
         * [activeId] is null when the encrypted store's active id must be left
         * exactly as it is.
         */
        data class Fold(
            val payload: String,
            val activeId: String?,
            val expectedIds: List<String>,
        ) : Plan()
    }

    /**
     * @param dirtyIds servers saved while the store was degraded. Their
     *        plaintext record is newer than anything in the encrypted store.
     * @param deletedIds servers deleted while degraded. They must not come back
     *        from the encrypted copy.
     * @param activeIdChosenWhileDegraded true when [plainActiveId] is a choice
     *        made during the degraded spell rather than a leftover.
     */
    fun plan(
        encryptedPayload: String?,
        encryptedActiveId: String?,
        plainPayload: String?,
        plainActiveId: String?,
        plainHasList: Boolean,
        dirtyIds: Set<String> = emptySet(),
        deletedIds: Set<String> = emptySet(),
        activeIdChosenWhileDegraded: Boolean = false,
    ): Plan {
        if (!plainHasList) return Plan.Nothing

        val plain = ServerStoreIntegrity.classify(plainPayload)
        if (plain is ServerStoreIntegrity.Verdict.Corrupt) {
            return Plan.Refuse("the plaintext server list is damaged")
        }
        val encrypted = ServerStoreIntegrity.classify(encryptedPayload)
        if (encrypted is ServerStoreIntegrity.Verdict.Corrupt) {
            // Merging would mean parsing what we just called unparseable, i.e.
            // dropping it. Leave both copies alone and let the loader shout.
            return Plan.Refuse("the encrypted server list is damaged")
        }

        val encryptedElements = elementsOf(encrypted)
        val plainElements = elementsOf(plain)

        // The plaintext record for every id that was written during the
        // degraded spell. These are the only plaintext records entitled to
        // displace an encrypted one.
        val newerInPlain = HashMap<String, String>()
        for (element in plainElements) {
            val id = idOf(element) ?: continue
            if (id in dirtyIds) newerInPlain[id] = element
        }

        val merged = mutableListOf<String>()
        val ids = mutableListOf<String?>()
        val placed = mutableSetOf<String>()

        // Encrypted first, in its own order, so the list the user knows keeps
        // its shape.
        for (element in encryptedElements) {
            val id = idOf(element)
            if (id == null) {
                // No readable id: cannot be matched or deduplicated, so it is
                // kept. A duplicate is repairable, a dropped server is not.
                merged.add(element)
                ids.add(null)
                continue
            }
            if (id in deletedIds) continue          // deleted while degraded; do not resurrect
            if (!placed.add(id)) continue
            merged.add(newerInPlain[id] ?: element) // the degraded write wins, if there was one
            ids.add(id)
        }

        // Then whatever plaintext has that is not placed yet — servers created
        // during the spell, and unmarked leftovers the encrypted store never
        // knew about.
        for (element in plainElements) {
            val id = idOf(element)
            if (id == null) {
                merged.add(element)
                ids.add(null)
                continue
            }
            if (id in deletedIds) continue
            if (!placed.add(id)) continue
            merged.add(element)
            ids.add(id)
        }

        // The active server: a choice made during the spell wins outright;
        // otherwise plaintext may only fill a gap. Never null — clearing the
        // key is what removed the active server, and an id that names nothing
        // is repaired by getActiveServer on the next read.
        val activeId = plainActiveId
            ?.takeIf { activeIdChosenWhileDegraded || encryptedActiveId == null }
            ?.takeIf { candidate -> ids.any { it == candidate } }

        return Plan.Fold(
            payload = merged.joinToString(prefix = "[", separator = ",", postfix = "]"),
            activeId = activeId,
            expectedIds = ids.filterNotNull(),
        )
    }

    private fun elementsOf(verdict: ServerStoreIntegrity.Verdict): List<String> = when (verdict) {
        is ServerStoreIntegrity.Verdict.Intact -> verdict.elements
        else -> emptyList()
    }

    /** True when [payload] contains a top-level record for every id in [ids]. */
    fun containsAll(payload: String?, ids: List<String>): Boolean {
        val elements = when (val verdict = ServerStoreIntegrity.classify(payload)) {
            is ServerStoreIntegrity.Verdict.Intact -> verdict.elements
            is ServerStoreIntegrity.Verdict.Empty -> emptyList()
            is ServerStoreIntegrity.Verdict.Corrupt -> return false
        }
        val present = elements.mapNotNull { idOf(it) }.toSet()
        return ids.all { it in present }
    }

    /**
     * The `id` of one stored record.
     *
     * Hand-rolled for the reason given on [JsonArraySplitter]: everything in
     * this file has to be answerable without org.json so that it can be tested
     * at all. Only top-level keys count — a nested `"id"` belongs to something
     * else.
     */
    fun idOf(element: String): String? {
        val text = element.trim()
        if (!text.startsWith("{")) return null

        var i = 1
        var depth = 0
        var expectKey = true
        var pendingKey: String? = null

        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> {
                    val read = readString(text, i) ?: return null
                    if (depth == 0) {
                        if (expectKey) pendingKey = read.first
                        else if (pendingKey == "id") return read.first
                    }
                    i = read.second
                }
                ':' -> { if (depth == 0) expectKey = false; i++ }
                ',' -> { if (depth == 0) { expectKey = true; pendingKey = null }; i++ }
                '{', '[' -> { depth++; i++ }
                '}', ']' -> { if (depth == 0) return null; depth--; i++ }
                else -> i++
            }
        }
        return null
    }

    /** @return the decoded string starting at [start], and the index after it. */
    private fun readString(text: String, start: Int): Pair<String, Int>? {
        val out = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> return out.toString() to (i + 1)
                '\\' -> {
                    if (i + 1 >= text.length) return null
                    when (val esc = text[i + 1]) {
                        '"', '\\', '/' -> { out.append(esc); i += 2 }
                        'b' -> { out.append('\b'); i += 2 }
                        'f' -> { out.append('\u000C'); i += 2 }
                        'n' -> { out.append('\n'); i += 2 }
                        'r' -> { out.append('\r'); i += 2 }
                        't' -> { out.append('\t'); i += 2 }
                        'u' -> {
                            if (i + 5 >= text.length) return null
                            val code = text.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                            out.append(code.toChar()); i += 6
                        }
                        else -> return null
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return null
    }
}
