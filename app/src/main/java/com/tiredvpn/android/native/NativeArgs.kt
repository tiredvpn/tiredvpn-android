package com.tiredvpn.android.native

/**
 * How a core argument list is written into the log.
 *
 * Every arg list handed to the core carries `-secret <key>`, and five call
 * sites used to render it with a bare `joinToString(" ")`. FileLogger writes to
 * a file the user can export and attach to a bug report, so that put a live
 * credential into a document meant to be shared.
 *
 * Redaction is positional - the token after a known secret-bearing flag -
 * rather than "any arg equal to the secret we happen to be holding". The value
 * comparison needs the secret in hand at the log site, which the two lowest
 * call sites do not have, and it also blanks an unrelated argument that happens
 * to match. The pool file makes this worse to get wrong than it was: the key
 * varies per server now, so a log site holding one of them would mask one line
 * and leak the next.
 */
object NativeArgs {

    private const val REDACTED = "***"

    /** Flags whose following token is a credential. */
    private val SECRET_FLAGS = setOf("-secret")

    /** The arg list as a single log line, credentials replaced. */
    fun redact(args: List<String>): String {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            out.add(arg)
            // A trailing flag with no value is left alone: there is nothing to
            // hide, and inventing a token would misreport what was passed.
            if (arg in SECRET_FLAGS && i + 1 < args.size) {
                out.add(REDACTED)
                i++
            }
            i++
        }
        return out.joinToString(" ")
    }

    fun redact(args: Array<String>): String = redact(args.asList())
}
