package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6GuardTest {

    @Test
    fun `blackhole address is a ULA`() {
        // fc00::/7 - must never be globally routable
        assertTrue(Ipv6Guard.BLACKHOLE_ADDRESS.startsWith("fd"))
        assertEquals(128, Ipv6Guard.BLACKHOLE_PREFIX_LENGTH)
    }

    @Test
    fun `canBlackhole requires the IPv6 minimum MTU`() {
        assertFalse(Ipv6Guard.canBlackhole(1279))
        assertFalse(Ipv6Guard.canBlackhole(1200))
        assertTrue(Ipv6Guard.canBlackhole(1280))
        assertTrue(Ipv6Guard.canBlackhole(1500))
    }

    @Test
    fun `canBlackhole treats non-positive MTU as platform default`() {
        assertTrue(Ipv6Guard.canBlackhole(0))
        assertTrue(Ipv6Guard.canBlackhole(-1))
    }

    @Test
    fun `IPv4 resolvers are not detected as IPv6`() {
        assertFalse(Ipv6Guard.isIpv6Literal("8.8.8.8"))
        assertFalse(Ipv6Guard.isIpv6Literal("1.1.1.1"))
        assertFalse(Ipv6Guard.isIpv6Literal(""))
        assertFalse(Ipv6Guard.isIpv6Literal("   "))
    }

    @Test
    fun `IPv6 resolvers are detected in every notation`() {
        assertTrue(Ipv6Guard.isIpv6Literal("2001:4860:4860::8888"))
        assertTrue(Ipv6Guard.isIpv6Literal("::1"))
        assertTrue(Ipv6Guard.isIpv6Literal("[2606:4700:4700::1111]"))
        assertTrue(Ipv6Guard.isIpv6Literal(" fe80::1%wlan0 "))
        assertTrue(Ipv6Guard.isIpv6Literal(Ipv6Guard.BLACKHOLE_ADDRESS))
    }
}
