package com.tiredvpn.android.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualStackPlanTest {

    private fun tunConfig(
        ip6: String? = null,
        serverIp6: String? = null,
        dns: String = "8.8.8.8",
        mtu: Int = 1280
    ) = TiredVpnService.TunnelConfig(
        ip = "10.8.0.2",
        serverIp = "10.8.0.1",
        dns = dns,
        mtu = mtu,
        routes = "0.0.0.0/0",
        ip6 = ip6,
        serverIp6 = serverIp6
    )

    // --- dual-stack on ---

    @Test
    fun `negotiated v6 installs real addresses and suppresses the blackhole`() {
        val plan = DualStackPlan.create(
            tunConfig(ip6 = "fd00:10:8::a08:2", serverIp6 = "fd00:10:8::1"),
            customDns = "",
            fallbackDns = "8.8.8.8",
            mtu = 1280
        )
        assertTrue(plan.dualStack)
        assertFalse(plan.blackhole)
        assertEquals("fd00:10:8::a08:2", plan.ip6)
        assertEquals("fd00:10:8::1", plan.serverIp6)
        assertEquals(64, DualStackPlan.IPV6_PREFIX_LENGTH)
    }

    @Test
    fun `dual-stack allows IPv6 DNS literals`() {
        val plan = DualStackPlan.create(
            tunConfig(ip6 = "fd00:10:8::a08:2", dns = "2001:4860:4860::8888"),
            customDns = "",
            fallbackDns = "8.8.8.8",
            mtu = 1280
        )
        assertTrue(plan.dualStack)
        assertEquals("2001:4860:4860::8888", plan.dns)
        assertFalse(plan.dnsSwappedToFallback)
    }

    @Test
    fun `dual-stack with custom IPv6 DNS override keeps it`() {
        val plan = DualStackPlan.create(
            tunConfig(ip6 = "fd00:10:8::a08:2"),
            customDns = "2606:4700:4700::1111",
            fallbackDns = "8.8.8.8",
            mtu = 1280
        )
        assertEquals("2606:4700:4700::1111", plan.dns)
        assertFalse(plan.dnsSwappedToFallback)
    }

    // --- dual-stack off: today's behaviour byte-for-byte ---

    @Test
    fun `no negotiated v6 keeps the blackhole`() {
        val plan = DualStackPlan.create(
            tunConfig(),
            customDns = "",
            fallbackDns = "8.8.8.8",
            mtu = 1280
        )
        assertFalse(plan.dualStack)
        assertTrue(plan.blackhole)
        assertNull(plan.ip6)
        assertNull(plan.serverIp6)
        assertEquals("8.8.8.8", plan.dns)
        assertFalse(plan.dnsSwappedToFallback)
    }

    @Test
    fun `IPv6 DNS behind the blackhole is swapped for the fallback`() {
        val plan = DualStackPlan.create(
            tunConfig(dns = "2001:4860:4860::8888"),
            customDns = "",
            fallbackDns = "8.8.8.8",
            mtu = 1280
        )
        assertFalse(plan.dualStack)
        assertTrue(plan.dnsSwappedToFallback)
        assertEquals("8.8.8.8", plan.dns)
        assertEquals("2001:4860:4860::8888", plan.requestedDns)
    }

    @Test
    fun `custom DNS override wins over core DNS`() {
        val plan = DualStackPlan.create(
            tunConfig(dns = "1.1.1.1"),
            customDns = "9.9.9.9",
            fallbackDns = "8.8.8.8",
            mtu = 1280
        )
        assertEquals("9.9.9.9", plan.dns)
        assertFalse(plan.dnsSwappedToFallback)
    }

    // --- MTU gate ---

    @Test
    fun `negotiated v6 below the minimum MTU degrades to the blackhole`() {
        val plan = DualStackPlan.create(
            tunConfig(ip6 = "fd00:10:8::a08:2"),
            customDns = "",
            fallbackDns = "8.8.8.8",
            mtu = 1200
        )
        assertFalse(plan.dualStack)
        assertTrue(plan.blackhole)
        assertNull(plan.ip6)
    }

    // --- TunnelConfig JSON parsing ---

    @Test
    fun `control JSON with dual-stack fields parses them`() {
        val json = JSONObject(
            """{"status":"waiting_fd","ip":"10.8.0.2","server_ip":"10.8.0.1",
               "dns":"8.8.8.8","mtu":1280,"routes":"0.0.0.0/0",
               "ip6":"fd00:10:8::a08:2","server_ip6":"fd00:10:8::1"}"""
        )
        val cfg = TiredVpnService.TunnelConfig.fromJson(json)
        assertEquals("fd00:10:8::a08:2", cfg.ip6)
        assertEquals("fd00:10:8::1", cfg.serverIp6)
    }

    @Test
    fun `control JSON without v6 fields leaves them null (old core)`() {
        val json = JSONObject(
            """{"status":"waiting_fd","ip":"10.8.0.2","server_ip":"10.8.0.1",
               "dns":"8.8.8.8","mtu":1280,"routes":"0.0.0.0/0"}"""
        )
        val cfg = TiredVpnService.TunnelConfig.fromJson(json)
        assertNull(cfg.ip6)
        assertNull(cfg.serverIp6)
        assertEquals("10.8.0.2", cfg.ip)
        assertEquals(1280, cfg.mtu)
    }

    @Test
    fun `control JSON with unknown fields is tolerated`() {
        val json = JSONObject(
            """{"status":"waiting_fd","ip":"10.8.0.2","future_field":42,
               "ip6":"fd00:10:8::a08:2","another_new":"x"}"""
        )
        val cfg = TiredVpnService.TunnelConfig.fromJson(json)
        assertEquals("fd00:10:8::a08:2", cfg.ip6)
        assertNull(cfg.serverIp6)
        // Defaults for absent optional fields
        assertEquals("10.8.0.1", cfg.serverIp)
        assertEquals("8.8.8.8", cfg.dns)
    }

    @Test
    fun `blank v6 strings are treated as absent`() {
        val json = JSONObject(
            """{"status":"waiting_fd","ip":"10.8.0.2","ip6":"","server_ip6":"  "}"""
        )
        val cfg = TiredVpnService.TunnelConfig.fromJson(json)
        assertNull(cfg.ip6)
        // "  " is not blank-checked... document actual behaviour:
        // takeIf { it.isNotBlank() } maps whitespace-only to null as well
        assertNull(cfg.serverIp6)
    }
}
