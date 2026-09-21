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
