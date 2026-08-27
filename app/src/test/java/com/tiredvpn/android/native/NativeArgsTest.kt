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
}
