package com.tiredvpn.android.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reconnect's answer naming another tunnel address. Missed, the interface
 * keeps the old address and the exit drops everything the phone sends, while
 * keepalives keep the session looking alive.
 */
class TunnelAddressesTest {

    @Test
    fun `a new address is a change`() {
        assertTrue(TunnelAddresses.ipv4Changed(current = "10.8.100.6", reported = "10.8.100.9"))
        // Another exit of the pool, another subnet.
        assertTrue(TunnelAddresses.ipv4Changed(current = "10.8.5.4", reported = "10.8.100.6"))
    }

    @Test
    fun `the same address is not`() {
        assertFalse(TunnelAddresses.ipv4Changed(current = "10.8.100.6", reported = "10.8.100.6"))
    }

    @Test
    fun `an answer that names no address says nothing`() {
        assertFalse(TunnelAddresses.ipv4Changed(current = "10.8.100.6", reported = ""))
        assertFalse(TunnelAddresses.ipv4Changed(current = "10.8.100.6", reported = null))
    }

    @Test
    fun `without an address in force there is nothing stale`() {
        assertFalse(TunnelAddresses.ipv4Changed(current = "", reported = "10.8.100.9"))
    }
}
