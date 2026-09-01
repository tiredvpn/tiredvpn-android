package com.tiredvpn.android.vpn

/**
 * Telling the core's two kinds of control-socket line apart.
 *
 * The core writes command responses and asynchronous events down the same
 * connection and distinguishes them by which field is present: a response
 * carries "status", an event carries "event" (internal/tun/control.go, where
 * ControlResponse and EventMessage share one net.Conn).
 *
 * The connection is registered as the event sink before the command loop
 * starts, so an event can land in front of the response we are waiting for.
 * Reading one line and testing it for status == "waiting_fd" then fails on a
 * perfectly healthy core, because the line examined was a keepalive.
 *
 * Keys are found with a small scanner rather than org.json, for two reasons:
 * org.json is stubbed out in unit tests and returns defaults, so anything
 * built on it here could not be tested; and the scanner only ever looks at
 * top-level keys, so an event whose "data" string happens to contain the text
 * `"status":` cannot be mistaken for a response.
 */
internal object ControlSocketProtocol {

    /** True when the line is an asynchronous event rather than a command response. */
    fun isEvent(line: String): Boolean {
        val keys = topLevelKeys(line) ?: return false
        return "status" !in keys && "event" in keys
    }

    /** True when the line answers the command we sent. */
    fun isResponse(line: String): Boolean = topLevelKeys(line)?.contains("status") == true

    /**
     * Keys of a JSON object at nesting depth one, or null if [line] is not a
     * JSON object. Values are skipped wholesale, so nested objects and strings
     * containing braces or quotes cannot contribute keys.
     */
    private fun topLevelKeys(line: String): Set<String>? {
        val text = line.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null

        val keys = mutableSetOf<String>()
        var i = 1
        var depth = 0
        var expectingKey = true

        while (i < text.length - 1) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++

                c == '"' -> {
                    val literal = readString(text, i) ?: return null
                    if (depth == 0 && expectingKey) {
                        // Only a name immediately followed by ':' is a key.
                        val after = skipWhitespace(text, literal.second)
                        if (after >= text.length || text[after] != ':') return null
                        keys.add(literal.first)
                        expectingKey = false
                        i = after + 1
                    } else {
                        i = literal.second
                    }
                }

                c == '{' || c == '[' -> { depth++; i++ }
                c == '}' || c == ']' -> { depth--; i++ }

                c == ',' -> {
                    if (depth == 0) expectingKey = true
                    i++
                }

                else -> i++
            }
        }
        return keys
    }

    /** Reads the string literal starting at [start]; returns its content and the index past it. */
    private fun readString(text: String, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '\\' -> {
                    if (i + 1 >= text.length) return null
                    sb.append(text[i + 1])
                    i += 2
                }
                '"' -> return sb.toString() to (i + 1)
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }
}
