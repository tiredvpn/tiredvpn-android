package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What survives an interface swap on a network change.
 *
 * The old path threw the negotiated config away and rebuilt one from
 * constants — `dns = "8.8.8.8"`, `mtu = 1280`, `serverIp = "10.9.0.1"` — so a
 * user-configured resolver worked until the first Wi-Fi/LTE switch and then
 * silently became Google's. The v6 addresses were already carried across, which
 * is what makes this a missing application of an existing mechanism rather than
 * a missing mechanism.
 */
class TunnelConfigTest {

    private fun negotiated() = TiredVpnService.TunnelConfig(
        ip = "10.8.0.7",
        serverIp = "10.8.0.1",
        dns = "1.1.1.1",
        mtu = 1400,
        routes = "0.0.0.0/0",
    )

    @Test
    fun `a network change keeps the negotiated resolver, mtu and server address`() {
        val after = negotiated().forNetworkChange(ip = "10.8.0.7", ip6 = null, serverIp6 = null)

        assertEquals("1.1.1.1", after.dns)
        assertEquals(1400, after.mtu)
        assertEquals("10.8.0.1", after.serverIp)
        assertEquals("0.0.0.0/0", after.routes)
    }

    @Test
    fun `a network change adopts the current tunnel address`() {
        val after = negotiated().forNetworkChange(ip = "10.8.0.9", ip6 = null, serverIp6 = null)
        assertEquals("10.8.0.9", after.ip)
    }

    @Test
    fun `dual-stack addresses are carried across and can be refreshed`() {
        val dual = negotiated().copy(ip6 = "fd00::7", serverIp6 = "fd00::1")

        val kept = dual.forNetworkChange(ip = "10.8.0.7", ip6 = null, serverIp6 = null)
        assertEquals("fd00::7", kept.ip6)
        assertEquals("fd00::1", kept.serverIp6)

        val refreshed = dual.forNetworkChange(ip = "10.8.0.7", ip6 = "fd00::8", serverIp6 = "fd00::2")
        assertEquals("fd00::8", refreshed.ip6)
        assertEquals("fd00::2", refreshed.serverIp6)
    }

    @Test
    fun `a v4-only session stays v4-only`() {
        val after = negotiated().forNetworkChange(ip = "10.8.0.7", ip6 = null, serverIp6 = null)
        assertNull(after.ip6)
        assertNull(after.serverIp6)
    }

    /**
     * The fallback used when a network change arrives before any handshake was
     * recorded. It has to agree with the core's own defaults, or the interface
     * comes up on an address the exit does not route.
     */
    @Test
    fun `the fallback config uses the addresses the core itself defaults to`() {
        val fallback = TiredVpnService.TunnelConfig.androidDefault()
        assertEquals("10.8.0.2", fallback.ip)
        assertEquals("10.8.0.1", fallback.serverIp)
        assertEquals("10.8.0.2", TiredVpnService.TunnelConfig.DEFAULT_TUN_IP)
        assertEquals("10.8.0.1", TiredVpnService.TunnelConfig.DEFAULT_SERVER_IP)
    }
}
