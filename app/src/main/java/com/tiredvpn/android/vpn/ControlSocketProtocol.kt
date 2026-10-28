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
     * True when the line can be the answer to `disconnect`.
     *
     * The core answers commands on a connection in the order it read them, so
     * the answer to a `status` poll or a `network_changed` sent just before the
     * stop can arrive first. Those are told apart by what the stop's answer
     * lacks: `{"status":"ok"}` carries no "connected" (omitempty false), and a
     * reconnect's answer has status "connected". An error answer counts: the
     * core has read the command, which is what the stop needs - it books the
     * user's stop when it reads `disconnect`, not when it answers.
     */
    fun isStopAnswer(line: String): Boolean {
        val fields = topLevelFields(line) ?: return false
        if ("event" in fields || "connected" in fields) return false
        return when (fields["status"]) {
            "ok", "error" -> true
            else -> false
        }
    }

    /**
     * A command line for the core: `{"command":"<name>", ...}` with the
     * [fields] that are not null, in order, every value a JSON string.
     *
     * Built here rather than with org.json so the wire form of the fields the
     * contract pins - `network_id` a string, absent rather than "" when there
     * is none - can be tested on the JVM, where org.json is a stub.
     */
    fun command(name: String, vararg fields: Pair<String, String?>): String {
        val sb = StringBuilder("{\"command\":").append(quote(name))
        for ((key, value) in fields) {
            if (value == null) continue
            sb.append(',').append(quote(key)).append(':').append(quote(value))
        }
        return sb.append('}').toString()
    }

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun topLevelKeys(line: String): Set<String>? = topLevelFields(line)?.keys

    /**
     * Keys of a JSON object at nesting depth one, each with its value when
     * that value is a string (null otherwise), or null if [line] is not a
     * JSON object. Other values are skipped wholesale, so nested objects and
     * strings containing braces or quotes cannot contribute keys.
     */
    private fun topLevelFields(line: String): Map<String, String?>? {
        val text = line.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null

        val fields = LinkedHashMap<String, String?>()
        var key: String? = null
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
                        key = literal.first
                        fields[literal.first] = null
                        expectingKey = false
                        i = after + 1
                    } else {
                        if (depth == 0 && key != null) fields[key] = literal.first
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
        return fields
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
