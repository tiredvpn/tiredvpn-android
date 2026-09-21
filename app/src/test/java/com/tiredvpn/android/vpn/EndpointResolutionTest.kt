package com.tiredvpn.android.vpn

import com.tiredvpn.android.vpn.EndpointResolution.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parse at the head of `TiredVpnService.resolveServerEndpoint`, which had
 * no test at all.
 *
 * Every connect that names a host goes through it. It decides three things —
 * malformed, already an address, or look it up — and the interesting half of
 * this file is the first: the parser rejects every IPv6 form the app can
 * store, and did so silently, logging "Invalid endpoint format" for an address
 * that is perfectly valid.
 */
class EndpointResolutionTest {

    // --- the ordinary shapes -------------------------------------------------

    @Test
    fun `a hostname and port are handed to DNS`() {
        assertEquals(Plan.Resolve("vpn.example.com", "995"), EndpointResolution.plan("vpn.example.com:995"))
    }

    @Test
    fun `an IPv4 literal skips the lookup entirely`() {
        assertEquals(Plan.AlreadyLiteral("31.44.3.165:995"), EndpointResolution.plan("31.44.3.165:995"))
    }

    /**
     * The literal branch returns the endpoint unchanged, port and all. An
     * earlier shape of this code rebuilt it as "$ip:$port" and would have
     * dropped anything the split did not capture.
     */
    @Test
    fun `the literal branch does not rebuild the string`() {
        val plan = EndpointResolution.plan("10.0.0.1:47123") as Plan.AlreadyLiteral
        assertEquals("10.0.0.1:47123", plan.endpoint)
    }

    @Test
    fun `a host with no port is malformed`() {
        assertEquals(Plan.Malformed, EndpointResolution.plan("vpn.example.com"))
    }

    @Test
    fun `an empty endpoint is malformed`() {
        assertEquals(Plan.Malformed, EndpointResolution.plan(""))
    }

    // --- the defect, pinned --------------------------------------------------

    /**
     * The bracketed form is what `VpnConfig.serverAddressV6` documents
     * ("format host:port or [v6]:port") and what every other part of the app
     * writes. This parser counts colons, finds seven, and calls it malformed.
     *
     * Asserted as [Plan.Malformed] on purpose: this records the bug rather
     * than fixing it, because the fix changes what the service does with a
     * v6 address and belongs in its own change. What the assertion buys is
     * that the fix cannot land without someone editing this line and noticing
     * the comment above it.
     */
    @Test
    fun `a bracketed IPv6 endpoint is rejected as malformed`() {
        assertEquals(Plan.Malformed, EndpointResolution.plan("[2001:470:1f0a:8eb::2]:995"))
        assertEquals(Plan.Malformed, EndpointResolution.plan("[::1]:995"))
    }

    @Test
    fun `a bare IPv6 address is rejected too`() {
        assertEquals(Plan.Malformed, EndpointResolution.plan("2001:470:1f0a:8eb::2"))
    }

    /**
     * The one IPv6 shape that gets through, and it gets through wrong: two
     * colons exactly, so the split succeeds and hands DNS the string "2001"
     * with "db8" as the port.
     */
    @Test
    fun `a two-colon IPv6 fragment is not rejected but is misread`() {
        assertEquals(Plan.Resolve("2001", "db8"), EndpointResolution.plan("2001:db8"))
    }

    // --- the IPv4 test is a shape test, not a validity test -------------------

    /**
     * The regex asks "four groups of one to three digits", not "a valid
     * address". Out-of-range octets take the literal branch and are passed to
     * the core as written. Harmless, but not what the branch name suggests,
     * and worth pinning so nobody tightens the regex believing it is already
     * strict.
     */
    @Test
    fun `an out-of-range dotted quad still counts as a literal`() {
        assertEquals(Plan.AlreadyLiteral("999.999.999.999:995"), EndpointResolution.plan("999.999.999.999:995"))
    }

    @Test
    fun `a three-octet address is not a literal and goes to DNS`() {
        assertTrue(EndpointResolution.plan("10.0.1:995") is Plan.Resolve)
    }

    @Test
    fun `a host that merely starts with digits is not a literal`() {
        assertTrue(EndpointResolution.plan("1.2.3.4.example.com:995") is Plan.Resolve)
    }

    /**
     * The regex is anchored at both ends. Without the anchors a hostname
     * containing a dotted quad would be treated as an address and never
     * resolved.
     */
    @Test
    fun `an address embedded in a longer host is not a literal`() {
        assertTrue(EndpointResolution.plan("host-1.2.3.4:995") is Plan.Resolve)
    }
}
