package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.net.InetAddress

class NetworkFingerprintTest {

    private fun addr(literal: String, prefix: Int) = InetAddress.getByName(literal) to prefix

    /**
     * The bug: onLinkPropertiesChanged filtered to Inet4Address only, so a
     * network that changed its IPv6 prefix while keeping the same v4 address
     * produced an identical fingerprint and no reconnect.
     */
    @Test
    fun `ipv6 prefix change is visible even when ipv4 is unchanged`() {
        val before = NetworkFingerprint.of(
            listOf(addr("192.168.1.65", 24), addr("2a00:1370:818c:2ed6::1234", 64))
        )
        val after = NetworkFingerprint.of(
            listOf(addr("192.168.1.65", 24), addr("2a00:1fa0:aaaa:bbbb::1234", 64))
        )
        assertNotEquals(before, after)
    }

    /** A v6-only network losing and regaining addressing must be visible too. */
    @Test
    fun `ipv6 only network change is visible`() {
        val before = NetworkFingerprint.of(listOf(addr("2001:470:1f0a:8eb::2", 64)))
        val after = NetworkFingerprint.of(listOf(addr("2001:470:8920:1::1", 64)))
        assertNotEquals(before, after)
    }

    /**
     * The trap on the other side: RFC 4941 temporary addresses rotate inside
     * one prefix. Comparing full v6 addresses would make every rotation look
     * like a network change and produce a reconnect storm.
     */
    @Test
    fun `temporary address rotation inside one prefix is not a change`() {
        val before = NetworkFingerprint.of(
            listOf(addr("192.168.1.65", 24), addr("2a00:1370:818c:2ed6:1111:2222:3333:4444", 64))
        )
        val after = NetworkFingerprint.of(
            listOf(addr("192.168.1.65", 24), addr("2a00:1370:818c:2ed6:9999:8888:7777:6666", 64))
        )
        assertEquals(before, after)
    }

    /** Several temporary addresses coexisting in one prefix collapse to one entry. */
    @Test
    fun `multiple addresses in one prefix collapse`() {
        val one = NetworkFingerprint.of(listOf(addr("2a00:1370:818c:2ed6::1", 64)))
        val many = NetworkFingerprint.of(
            listOf(
                addr("2a00:1370:818c:2ed6::1", 64),
                addr("2a00:1370:818c:2ed6:aaaa::2", 64),
                addr("2a00:1370:818c:2ed6:bbbb::3", 64)
            )
        )
        assertEquals(one, many)
    }

    /** Link-local only means "no usable network", not "a new network". */
    @Test
    fun `link local addresses are ignored`() {
        val global = NetworkFingerprint.of(listOf(addr("192.168.1.65", 24)))
        val globalPlusLinkLocal = NetworkFingerprint.of(
            listOf(
                addr("192.168.1.65", 24),
                addr("fe80::1c9a:5cff:fe23:1", 64),
                addr("169.254.10.20", 16)
            )
        )
        assertEquals(global, globalPlusLinkLocal)
    }

    /** A real IPv4 move must still be caught — the old behaviour we keep. */
    @Test
    fun `ipv4 address change is visible`() {
        val wifi = NetworkFingerprint.of(listOf(addr("192.168.1.65", 24)))
        val lte = NetworkFingerprint.of(listOf(addr("11.10.27.248", 30)))
        assertNotEquals(wifi, lte)
    }

    /** Callback ordering must not matter. */
    @Test
    fun `fingerprint is order independent`() {
        val a = NetworkFingerprint.of(
            listOf(addr("192.168.1.65", 24), addr("2a00:1370:818c:2ed6::1", 64))
        )
        val b = NetworkFingerprint.of(
            listOf(addr("2a00:1370:818c:2ed6::1", 64), addr("192.168.1.65", 24))
        )
        assertEquals(a, b)
    }

    /** Losing all addressing is a change, not a no-op. */
    @Test
    fun `losing all addresses is a change`() {
        val up = NetworkFingerprint.of(listOf(addr("192.168.1.65", 24)))
        val down = NetworkFingerprint.of(emptyList())
        assertNotEquals(up, down)
        assertEquals("", down)
    }

    /**
     * Positive control for the mask itself: a prefix shorter than /64 must
     * ignore bits the longer prefix keeps. Without this, prefixOf() could
     * hardcode /64 and every test above would still pass.
     */
    @Test
    fun `prefix length is honoured, not assumed to be 64`() {
        val slash32 = NetworkFingerprint.of(listOf(addr("2a00:1370:818c:2ed6::1", 32)))
        val slash32Other = NetworkFingerprint.of(listOf(addr("2a00:1370:9999:9999::1", 32)))
        assertEquals(slash32, slash32Other)

        val slash64 = NetworkFingerprint.of(listOf(addr("2a00:1370:818c:2ed6::1", 64)))
        val slash64Other = NetworkFingerprint.of(listOf(addr("2a00:1370:9999:9999::1", 64)))
        assertNotEquals(slash64, slash64Other)
    }
}
