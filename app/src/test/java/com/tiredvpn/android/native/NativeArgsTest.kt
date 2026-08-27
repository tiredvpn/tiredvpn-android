package com.tiredvpn.android.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileLogger output is exportable and gets attached to bug reports, so the
 * assertions here are about a value being absent from a string, not about
 * formatting.
 */
class NativeArgsTest {

    @Test
    fun `the token after -secret is replaced`() {
        val line = NativeArgs.redact(
            listOf("client", "-secret", "s3cr3t-value", "-server", "198.51.100.1:995")
        )

        assertEquals("client -secret *** -server 198.51.100.1:995", line)
        assertTrue(line, !line.contains("s3cr3t-value"))
    }

    @Test
    fun `an arg that merely equals the secret elsewhere is left alone`() {
        // Positional, not value-based: -cover really was called "s3cr3t-value"
        // here, and blanking it would misreport the command that ran.
        val line = NativeArgs.redact(listOf("-secret", "s3cr3t-value", "-cover", "s3cr3t-value"))

        assertEquals("-secret *** -cover s3cr3t-value", line)
    }

    @Test
    fun `a trailing -secret with no value invents nothing`() {
        assertEquals("-tun -secret", NativeArgs.redact(listOf("-tun", "-secret")))
    }

    @Test
    fun `every other arg survives untouched`() {
        val args = listOf("-strategy", "morph_Yandex Video", "-tun", "-tun-ip", "auto")

        assertEquals("-strategy morph_Yandex Video -tun -tun-ip auto", NativeArgs.redact(args))
    }

    @Test
    fun `an empty list renders as an empty line`() {
        assertEquals("", NativeArgs.redact(emptyList()))
    }

    @Test
    fun `the array overload behaves like the list one`() {
        assertEquals(
            NativeArgs.redact(listOf("-secret", "k", "-tun")),
            NativeArgs.redact(arrayOf("-secret", "k", "-tun"))
        )
    }

    // --- the shell wrapper ---
    //
    // NativeProcess runs the core as `sh -c "<command line>"`, which collapses
    // every argument into one element. Element-wise redaction walks past a key
    // sitting inside that string, and it did: the wrapper was logged through
    // plain redact() and the secret went into the log in full. These assertions
    // are on the path NativeProcess actually takes, not on redact() in
    // isolation - which is exactly what stayed green while the leak was live.

    /** The arg list NativeProcess is constructed with, in the real order. */
    private fun coreArgs(secret: String = "s3cr3t-value") = listOf(
        "/data/app/lib/libtiredvpn.so", "client",
        "-secret", secret,
        "-listen", "127.0.0.1:8080",
        "-strategy", "morph_Yandex Video"
    )

    @Test
    fun `the wrapper log shows sh -c and no key`() {
        val line = NativeArgs.shellWrapperLog(coreArgs())

        assertTrue(line, !line.contains("s3cr3t-value"))
        assertTrue(line, line.startsWith("/system/bin/sh -c "))
        assertTrue(line, line.contains("-secret ***"))
    }

    @Test
    fun `the wrapper log keeps the quoting the shell will see`() {
        val line = NativeArgs.shellWrapperLog(coreArgs())
        val command = NativeArgs.shellWrapper(coreArgs()).last()

        // Same joiner as the real command line: an arg with a space stays quoted.
        assertTrue(line, line.contains("""-strategy "morph_Yandex Video""""))
        assertTrue(command, command.contains("""-strategy "morph_Yandex Video""""))
        // Identical but for the key, so the log cannot describe a different run.
        assertEquals(command.replace("s3cr3t-value", "***"), line.removePrefix("/system/bin/sh -c "))
    }

    @Test
    fun `the wrapper itself carries the real key, which is why it is not what gets logged`() {
        val wrapper = NativeArgs.shellWrapper(coreArgs())

        assertEquals(listOf("/system/bin/sh", "-c"), wrapper.dropLast(1))
        assertTrue(wrapper.toString(), wrapper.last().contains("-secret s3cr3t-value"))
    }

    @Test
    fun `a joined command line handed to redact is blanked whole, not passed through`() {
        // The safety net under the mistake above: a caller that joins before
        // redacting used to leak the key verbatim. The element cannot be picked
        // apart safely - the key may contain a space, and then the joiner quoted
        // it - so the whole element goes.
        val line = NativeArgs.redact(NativeArgs.shellWrapper(coreArgs()))

        assertTrue(line, !line.contains("s3cr3t-value"))
        assertEquals("/system/bin/sh -c ***", line)
    }

    @Test
    fun `a key containing a space and a quote does not survive any of the three routes`() {
        val nasty = """my key" -listen"""
        val args = coreArgs(secret = nasty)

        for (line in listOf(
            NativeArgs.redact(args),
            NativeArgs.shellWrapperLog(args),
            NativeArgs.redact(NativeArgs.shellWrapper(args))
        )) {
            assertTrue(line, !line.contains(nasty))
        }
    }

    @Test
    fun `the bare flag is not mistaken for an embedded command line`() {
        // The embedded-command-line branch looks for "-secret " inside an
        // element; the flag on its own must still take the positional branch.
        assertEquals("-secret *** -tun", NativeArgs.redact(listOf("-secret", "k", "-tun")))
    }
}
