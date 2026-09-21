package com.tiredvpn.android.ui

/**
 * What the settings and server forms accept, in one place.
 *
 * The dialogs used to each decide for themselves, and mostly decided nothing:
 * a blank port silently became 993, a proxy port outside the range was dropped
 * without a word, an IPv6 endpoint and an ECH config were stored exactly as
 * typed. A value that cannot work should be refused at the field, with a
 * reason, rather than stored and discovered at connect time.
 *
 * Every function here is pure - no Context, no android.util.Patterns - so the
 * rules can be pinned by tests instead of by trying dialogs by hand.
 */
object InputValidation {

    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    /**
     * Lowest port an unprivileged local listener can bind. Applies to the HTTP
     * proxy we run on the device, not to the port of a remote server.
     */
    const val MIN_USER_PORT = 1024

    /**
     * The port in [raw], or null if it is missing, not a number, or out of
     * range. Surrounding whitespace is the user's, not an error.
     */
    fun parsePort(raw: String?, min: Int = MIN_PORT, max: Int = MAX_PORT): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return null
        val port = text.toIntOrNull() ?: return null   // also catches overflow
        return if (port in min..max) port else null
    }

    /** A host a connection could be opened to: a name or an IPv4 literal. */
    fun isValidHost(value: String): Boolean =
        isIpv4Literal(value) || isHostname(value)

    /**
     * DNS name, ASCII only. Unicode names would have to be punycoded before
     * they reach the core, and nothing in the client does that, so refuse them
     * here rather than fail at resolve time.
     */
    fun isHostname(value: String): Boolean {
        if (value.isEmpty() || value.length > 253) return false
        return value.split(".").all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                !label.startsWith("-") && !label.endsWith("-") &&
                label.all { (it.isLetterOrDigit() && it.code < 128) || it == '-' }
        }
    }

    fun isIpv4Literal(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 &&
                part.all { it in '0'..'9' } && part.toInt() <= 255
        }
    }

    /**
     * IPv6 literal, with an optional zone id (`fe80::1%wlan0`) and the v4-mapped
     * tail form (`::ffff:1.2.3.4`). Written out rather than delegated to
     * InetAddress, which resolves names, or to android.net.InetAddresses, which
     * needs API 29.
     */
    fun isIpv6Literal(value: String): Boolean {
        val address = value.substringBefore('%')
        if (address.isEmpty() || address.length > 45) return false

        val sides = address.split("::")
        if (sides.size > 2) return false          // "1::2::3" is ambiguous
        val elided = sides.size == 2

        var groups = 0

        sides.forEachIndexed { sideIndex, side ->
            if (side.isEmpty()) return@forEachIndexed   // "::1" / "1::" / "::"
            val fields = side.split(":")
            fields.forEachIndexed { index, field ->
                if (field.isEmpty()) return false // ":::", "1:" and friends
                val tail = sideIndex == sides.lastIndex && index == fields.lastIndex
                if (tail && field.contains('.')) {
                    if (!isIpv4Literal(field)) return false
                    groups += 2
                } else {
                    if (field.length > 4 || !field.all { it.isHexDigit() }) return false
                    groups += 1
                }
            }
        }

        return if (elided) groups < 8 else groups == 8
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * The IPv6 endpoint field: `host:port`, `[literal]:port`, or empty to turn
     * the endpoint off. Returns the trimmed value, or null when it is neither.
     *
     * A bare literal is refused on purpose: `2001:db8::2:995` is a valid
     * address, and guessing that the last group was meant as a port is how you
     * dial the wrong host.
     */
    fun parseV6Endpoint(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""

        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0 || !text.startsWith("]:", close)) return null
            if (!isIpv6Literal(text.substring(1, close))) return null
            return if (parsePort(text.substring(close + 2)) != null) text else null
        }

        val colon = text.lastIndexOf(':')
        if (colon <= 0 || text.indexOf(':') != colon) return null   // unbracketed literal
        if (!isValidHost(text.substring(0, colon))) return null
        return if (parsePort(text.substring(colon + 1)) != null) text else null
    }

    /**
     * Standard base64, whitespace ignored - configs get pasted out of terminals
     * with line breaks in them. Only the shape is checked; whether the bytes
     * are a sane ECHConfigList is for the core to say.
     */
    fun isBase64(value: String): Boolean {
        val text = value.filterNot { it.isWhitespace() }
        if (text.isEmpty() || text.length % 4 != 0) return false

        val body = text.trimEnd('=')
        val padding = text.length - body.length
        if (padding > 2) return false
        if (body.any { !(it.isLetterOrDigit() && it.code < 128) && it != '+' && it != '/' }) return false
        return true
    }
}
