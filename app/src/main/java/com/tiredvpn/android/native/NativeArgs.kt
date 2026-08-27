package com.tiredvpn.android.native

/**
 * How a core argument list is written into the log, and how the shell wrapper
 * around it is built.
 *
 * Every arg list handed to the core carries `-secret <key>`, and eight call
 * sites rendered it into `FileLogger` with a bare `joinToString(" ")`.
 * FileLogger writes to a file the user can export and attach to a bug report,
 * so that put a live credential into a document meant to be shared.
 *
 * Redaction is positional - the token after a known secret-bearing flag -
 * rather than "any arg equal to the secret we happen to be holding". The value
 * comparison needs the secret in hand at the log site, which the two lowest
 * call sites do not have, and it also blanks an unrelated argument that happens
 * to match. The pool file makes this worse to get wrong than it was: the key
 * varies per server now, so a log site holding one of them would mask one line
 * and leak the next.
 *
 * The shell wrapper lives here rather than in [NativeProcess] because
 * positional redaction only works while the flag and its value are still
 * separate elements. The wrapper collapses the whole command into one string,
 * and redacting that after the fact is guesswork - see [shellWrapperLog].
 * Keeping the wrapper and its log line in one place is what stops the two from
 * describing different commands.
 */
object NativeArgs {

    private const val REDACTED = "***"

    /** Flags whose following token is a credential. */
    private val SECRET_FLAGS = setOf("-secret")

    /**
     * The shell Android 10+ makes us go through: a binary in app storage cannot
     * be executed directly, so it is run as `sh -c "<command line>"`.
     */
    const val SHELL = "/system/bin/sh"

    /** The arg list with credentials replaced, still one token per element. */
    fun redacted(args: List<String>): List<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                // A whole command line arriving as one element - a caller that
                // joined before redacting. The flag is inside the string, where
                // element matching cannot see it, so the element goes as a
                // whole. Cutting the key out at the next separator would be
                // guessing where it ends: a key may contain a space, and then
                // the joiner quoted it.
                carriesEmbeddedSecret(arg) -> out.add(REDACTED)

                // A trailing flag with no value is left alone: there is nothing
                // to hide, and inventing a token would misreport what was passed.
                arg in SECRET_FLAGS && i + 1 < args.size -> {
                    out.add(arg)
                    out.add(REDACTED)
                    i++
                }

                else -> out.add(arg)
            }
            i++
        }
        return out
    }

    /** The arg list as a single log line, credentials replaced. */
    fun redact(args: List<String>): String = redacted(args).joinToString(" ")

    fun redact(args: Array<String>): String = redact(args.asList())

    /** The argv actually executed: [SHELL], `-c`, and the command line. */
    fun shellWrapper(args: List<String>): List<String> =
        listOf(SHELL, "-c", joinForShell(args))

    /**
     * The wrapper as a log line, credentials replaced.
     *
     * Built from the flat args and joined afterwards, which is the opposite
     * order from [shellWrapper] on purpose: redaction has to happen while the
     * key is still its own element. Everything else goes through the same
     * [joinForShell] as the real command, so the line still shows the quoting
     * the shell will see.
     */
    fun shellWrapperLog(args: List<String>): String =
        "${joinForShell(redacted(args))}"

    /** Join into one shell word list, quoting args that contain spaces. */
    private fun joinForShell(args: List<String>): String =
        args.joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }

    /** Does this element look like a joined command line with a flag inside it? */
    private fun carriesEmbeddedSecret(arg: String): Boolean =
        arg !in SECRET_FLAGS && SECRET_FLAGS.any { arg.contains("$it ") || arg.contains("$it\"") }
}
