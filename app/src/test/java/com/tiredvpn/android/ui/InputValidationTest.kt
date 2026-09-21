package com.tiredvpn.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the dialogs of this app accept.
 *
 * Every rule here exists because some form used to take the value silently:
 * an empty port became 993, a proxy port of 70000 was dropped without a word,
 * an IPv6 endpoint was stored as typed however it was typed.
 */
class InputValidationTest {

    // --- ports ---

    @Test
    fun `a good port parses`() {
        assertEquals(993, InputValidation.parsePort("993"))
        assertEquals(1, InputValidation.parsePort("1"))
        assertEquals(65535, InputValidation.parsePort("65535"))
        assertEquals("surrounding spaces are the user's, not an error", 443, InputValidation.parsePort(" 443 "))
    }

    @Test
    fun `nothing, junk and out-of-range are all rejected`() {
        assertNull(InputValidation.parsePort(null))
        assertNull(InputValidation.parsePort(""))
        assertNull(InputValidation.parsePort("   "))
        assertNull(InputValidation.parsePort("abc"))
        assertNull(InputValidation.parsePort("80x"))
        assertNull(InputValidation.parsePort("8 0"))
        assertNull(InputValidation.parsePort("+80"))
        assertNull(InputValidation.parsePort("-1"))
        assertNull("port 0 is not a port", InputValidation.parsePort("0"))
        assertNull(InputValidation.parsePort("65536"))
        assertNull(InputValidation.parsePort("99999999999999999999"))
    }

    @Test
    fun `a minimum can be raised for local listeners`() {
        assertNull(
            "a local listener cannot bind a privileged port",
            InputValidation.parsePort("80", min = InputValidation.MIN_USER_PORT)
        )
        assertEquals(8080, InputValidation.parsePort("8080", min = InputValidation.MIN_USER_PORT))
    }

    // --- hosts ---

    @Test
    fun `hosts that a connection could actually use`() {
        assertTrue(InputValidation.isValidHost("api.googleapis.com"))
        assertTrue(InputValidation.isValidHost("vk.com"))
        assertTrue(InputValidation.isValidHost("localhost"))
        assertTrue(InputValidation.isValidHost("my-server-1.example.org"))
        assertTrue(InputValidation.isValidHost("192.168.1.1"))
    }

    @Test
    fun `hosts that are not hosts`() {
        assertFalse(InputValidation.isValidHost(""))
        assertFalse(InputValidation.isValidHost("   "))
        assertFalse(InputValidation.isValidHost("not a host"))
        assertFalse(InputValidation.isValidHost("https://example.com"))
        assertFalse(InputValidation.isValidHost("example.com/path"))
        assertFalse(InputValidation.isValidHost("example..com"))
        assertFalse(InputValidation.isValidHost("-example.com"))
        assertFalse(InputValidation.isValidHost("example-.com"))
        assertFalse(InputValidation.isValidHost("пример.рф"))
        assertFalse(InputValidation.isValidHost("a".repeat(64) + ".com"))
    }

    // --- IPv6 literals ---

    @Test
    fun `IPv6 literals`() {
        assertTrue(InputValidation.isIpv6Literal("2001:470:1f0a:8eb::2"))
        assertTrue(InputValidation.isIpv6Literal("::1"))
        assertTrue(InputValidation.isIpv6Literal("::"))
        assertTrue(InputValidation.isIpv6Literal("fe80::1%wlan0"))
        assertTrue(InputValidation.isIpv6Literal("2001:db8:0:0:0:0:0:1"))
        assertTrue("v4-mapped", InputValidation.isIpv6Literal("::ffff:192.168.1.1"))
    }

    @Test
    fun `things that only look like IPv6`() {
        assertFalse(InputValidation.isIpv6Literal(""))
        assertFalse(InputValidation.isIpv6Literal("2001:db8"))
        assertFalse("two elisions are ambiguous", InputValidation.isIpv6Literal("2001::db8::1"))
        assertFalse(InputValidation.isIpv6Literal("2001:db8:0:0:0:0:0:1:2"))
        assertFalse(InputValidation.isIpv6Literal("2001:zz8::1"))
        assertFalse(InputValidation.isIpv6Literal("20011:db8::1"))
        assertFalse(InputValidation.isIpv6Literal("::ffff:192.168.1.256"))
        assertFalse(InputValidation.isIpv6Literal("1.2.3.4"))
    }

    // --- IPv6 endpoints ---

    @Test
    fun `an empty endpoint means off`() {
        assertEquals("", InputValidation.parseV6Endpoint(""))
        assertEquals("", InputValidation.parseV6Endpoint("  "))
        assertEquals("", InputValidation.parseV6Endpoint(null))
    }

    @Test
    fun `endpoints in the two documented shapes`() {
        assertEquals("[2001:470:1f0a:8eb::2]:995", InputValidation.parseV6Endpoint("[2001:470:1f0a:8eb::2]:995"))
        assertEquals("[::1]:1", InputValidation.parseV6Endpoint(" [::1]:1 "))
        assertEquals("ams.example:995", InputValidation.parseV6Endpoint("ams.example:995"))
    }

    @Test
    fun `endpoints missing a port or brackets are rejected`() {
        assertNull("a bare literal has no port", InputValidation.parseV6Endpoint("2001:470:1f0a:8eb::2"))
        assertNull("unbracketed literal with port is unparseable", InputValidation.parseV6Endpoint("2001:db8::2:995"))
        assertNull(InputValidation.parseV6Endpoint("ams.example"))
        assertNull(InputValidation.parseV6Endpoint("ams.example:"))
        assertNull(InputValidation.parseV6Endpoint("ams.example:0"))
        assertNull(InputValidation.parseV6Endpoint("ams.example:70000"))
        assertNull(InputValidation.parseV6Endpoint("[2001:db8::1]"))
        assertNull(InputValidation.parseV6Endpoint("[not:an:address]:995"))
        assertNull(InputValidation.parseV6Endpoint("[2001:db8::1]:abc"))
    }

    // --- base64 ---

    @Test
    fun `base64 that decodes`() {
        assertTrue(InputValidation.isBase64("AEX+DQBBAAAgACDS"))
        assertTrue("padding is allowed", InputValidation.isBase64("QUJD"))
        assertTrue(InputValidation.isBase64("QQ=="))
        assertTrue(InputValidation.isBase64("QUI="))
        assertTrue("wrapped config pasted from a terminal", InputValidation.isBase64("QUJD\nRUZH"))
    }

    @Test
    fun `base64 that does not`() {
        assertFalse(InputValidation.isBase64(""))
        assertFalse(InputValidation.isBase64("   "))
        assertFalse("one leftover character cannot be a byte", InputValidation.isBase64("QUJDR"))
        assertFalse(InputValidation.isBase64("not base64!"))
        assertFalse("padding belongs at the end", InputValidation.isBase64("QU=D"))
        assertFalse(InputValidation.isBase64("QUJD==="))
    }
}
