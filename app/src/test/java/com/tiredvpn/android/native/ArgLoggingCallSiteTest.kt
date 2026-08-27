package com.tiredvpn.android.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the CALL SITES that log a core argument list, rather than the function
 * they are supposed to call.
 *
 * This exists because the obvious tests did not catch the leak. `NativeArgs`
 * was covered, `redact` behaved exactly as specified, and every assertion was
 * green while `NativeProcess` handed it an already-joined command line and put
 * a live key in the log. Swapping the call site back to the broken form still
 * fails nothing in `NativeArgsTest`: the function is fine, its use was not.
 *
 * None of the eight sites can be reached from a JVM unit test - they sit inside
 * a `VpnService`, a `Runtime.exec` path and a JNI wrapper - so the property is
 * asserted against the source text. That is blunt, and it is a deliberate
 * trade: a rule that can be checked beats a rule that is merely written down in
 * a comment above the line that broke.
 */
class ArgLoggingCallSiteTest {

    /**
     * Kotlin sources of the app module. Gradle runs unit tests with the module
     * directory as the working directory; the walk up is for anyone running
     * them from the repository root instead.
     */
    private fun mainSources(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        val root = File(requireNotNull(dir) { "cannot locate src/main/java from ${File("").absolutePath}" }, "src/main/java")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private data class Offence(val file: String, val line: Int, val text: String)

    private fun scan(match: (String) -> Boolean): List<Offence> =
        mainSources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (match(line)) Offence(f.name, i + 1, line.trim()) else null
            }
        }

    @Test
    fun `sources are actually being scanned`() {
        // Rule 2: an empty result has to be distinguishable from a scanner that
        // reads nothing. NativeArgs.kt is known to exist and to say "-secret".
        val files = mainSources()
        assertTrue("no sources found", files.size > 10)
        assertTrue(
            "scanner sees no NativeArgs.kt",
            files.any { it.name == "NativeArgs.kt" && it.readText().contains("SECRET_FLAGS") }
        )
        assertTrue("scanner finds nothing at all", scan { it.contains("FileLogger") }.isNotEmpty())
    }

    @Test
    fun `no log line renders an argument list with a bare joinToString`() {
        // The original leak at all eight sites, and the shape to never restore:
        // "${args.joinToString(" ")}" inside a FileLogger call.
        val offences = scan { line ->
            line.contains("FileLogger") && line.contains("joinToString") &&
                Regex("""\b(args|argv|shellArgs|cmdLine)\b""").containsMatchIn(line)
        }

        assertEquals(offences.joinToString("\n"), emptyList<Offence>(), offences)
    }

    @Test
    fun `redaction is never applied to an already-joined command line`() {
        // NativeArgs.redact matches elements. Handing it the shell wrapper puts
        // the flag and its key inside one element, where element matching cannot
        // see them - which is how the wrapper site leaked. The safety net inside
        // redact turns that into a useless log line rather than a leak, so this
        // rule protects the log's usefulness as much as the key.
        val offences = scan { line ->
            Regex("""redact\(\s*(NativeArgs\.)?shellWrapper\(""").containsMatchIn(line) ||
                Regex("""redact\(\s*shellArgs\b""").containsMatchIn(line) ||
                Regex("""redact\(\s*cmdLine\b""").containsMatchIn(line)
        }

        assertEquals(offences.joinToString("\n"), emptyList<Offence>(), offences)
    }

    @Test
    fun `the shell wrapper is built and logged through NativeArgs`() {
        val src = mainSources().single { it.name == "NativeProcess.kt" }.readText()

        assertTrue("NativeProcess must build the wrapper via NativeArgs.shellWrapper",
            src.contains("NativeArgs.shellWrapper(args)"))
        assertTrue("NativeProcess must log the wrapper via NativeArgs.shellWrapperLog",
            src.contains("NativeArgs.shellWrapperLog(args)"))
        // The hand-rolled wrapper this replaced, so it cannot quietly come back
        // alongside the NativeArgs one.
        assertTrue("NativeProcess must not re-introduce a hand-built sh -c wrapper",
            !src.contains("""mutableListOf("/system/bin/sh""""))
    }

    @Test
    fun `both core launch paths still pass -secret`() {
        // Not about logging, but the same kind of hole: ServerPoolConfig.render
        // documents -secret as inert once every pool entry names its own key,
        // and someone acting on that sentence alone would delete the flag. It is
        // not inert on the path a one-server pool degenerates to, where no file
        // is written and this is the only key the core gets.
        val src = mainSources().single { it.name == "TiredVpnService.kt" }.readText()

        assertEquals(
            "both startTiredVpnProcess and startTiredVpnProxyProcess must pass -secret",
            2,
            Regex("""^\s*"-secret", config\.secret,$""", RegexOption.MULTILINE).findAll(src).count()
        )
    }

    @Test
    fun `every flag carrying a credential is declared in SECRET_FLAGS`() {
        // Audited by listing every "-flag" literal the app passes to the core.
        // Of the thirty, one carries a credential. -ech-config is a public
        // ECHConfigList published in DNS, -shaper-seed picks a shaping profile,
        // and -config is a path - the file it names holds the keys, and it is
        // the path that gets logged, not the contents.
        val flags = mainSources()
            .flatMap { Regex(""""(-[a-z0-9-]+)"""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSortedSet()

        assertTrue("expected the known flag set, got $flags", flags.contains("-secret"))
        assertEquals(
            "a new flag appeared - decide whether it carries a credential, then update this list",
            setOf(
                "-android", "-c", "-config", "-control-socket", "-cover", "-cover-host", "-debug",
                "-ech", "-ech-config", "-ech-public-name", "-fallback", "-fallback-v4", "-listen",
                "-prefer-ipv6", "-protect-path", "-quic", "-quic-port", "-quic-sni-frag",
                "-rtt-masking", "-rtt-profile", "-secret", "-server", "-server-v6", "-shaper",
                "-shaper-seed", "-strategy", "-tun", "-tun-ip", "-tun-ipv6", "-tun-mtu"
            ),
            flags.toSet()
        )
    }
}
