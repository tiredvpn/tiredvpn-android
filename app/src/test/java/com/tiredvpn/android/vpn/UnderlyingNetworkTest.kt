package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The network the core is told it runs on (`network_id`).
 *
 * The core keeps one core's server verdicts for the next only on the same
 * network. Naming the VPN's own network - what `activeNetwork` can return with
 * the tunnel up - makes Wi-Fi and LTE look like one network, and the verdicts
 * a dead network's path produced ride over onto the next.
 */
class UnderlyingNetworkTest {

    private val wifi = UnderlyingNetwork.Candidate("wifi", 432902426637L, isVpn = false, hasInternet = true, validated = true, kind = NetworkInUse.Kind.WIFI)
    private val lte = UnderlyingNetwork.Candidate("lte", 515396075533L, isVpn = false, hasInternet = true, validated = true, kind = NetworkInUse.Kind.CELLULAR)
    private val vpn = UnderlyingNetwork.Candidate("vpn", 618475290637L, isVpn = true, hasInternet = true, validated = true, kind = NetworkInUse.Kind.OTHER)

    @Test
    fun `the VPN is never the underlying network, even as the active one`() {
        assertEquals(lte, UnderlyingNetwork.choose(tracked = null, active = "vpn", all = listOf(vpn, lte)))
        assertNull(UnderlyingNetwork.choose(tracked = "vpn", active = "vpn", all = listOf(vpn)))
    }

    @Test
    fun `the network the callback has traffic on wins over the system default`() {
        // A network_changed is about the move the callback saw; the default can
        // still name the network being left.
        assertEquals(lte, UnderlyingNetwork.choose(tracked = "lte", active = "wifi", all = listOf(wifi, lte)))
    }

    @Test
    fun `a tracked network that is gone or a VPN is not taken`() {
        assertEquals(wifi, UnderlyingNetwork.choose(tracked = "gone", active = "wifi", all = listOf(wifi, lte)))
        assertEquals(wifi, UnderlyingNetwork.choose(tracked = "vpn", active = "wifi", all = listOf(vpn, wifi, lte)))
    }

    @Test
    fun `without a callback the non-VPN default is taken`() {
        assertEquals(lte, UnderlyingNetwork.choose(tracked = null, active = "lte", all = listOf(wifi, lte)))
    }

    @Test
    fun `without either, the best non-VPN network with internet`() {
        val unvalidatedWifi = wifi.copy(validated = false)
        val noInternetEth = wifi.copy(network = "eth", handle = 1L, hasInternet = false, kind = NetworkInUse.Kind.ETHERNET)
        assertEquals(wifi, UnderlyingNetwork.choose(tracked = null, active = null, all = listOf(lte, wifi, vpn)))
        assertEquals(lte, UnderlyingNetwork.choose(tracked = null, active = null, all = listOf(unvalidatedWifi, lte)))
        assertEquals(lte, UnderlyingNetwork.choose(tracked = null, active = null, all = listOf(noInternetEth, lte)))
    }

    @Test
    fun `no underlying network is none, not a guess`() {
        assertNull(UnderlyingNetwork.choose<String>(tracked = null, active = null, all = emptyList()))
        // Handle 0 is NETWORK_UNSPECIFIED, not a network.
        assertNull(UnderlyingNetwork.choose(tracked = "wifi", active = "wifi", all = listOf(wifi.copy(handle = 0L))))
    }

    @Test
    fun `the wire id is the handle in decimal, and absent rather than empty`() {
        assertEquals("432902426637", UnderlyingNetwork.wireId(432902426637L))
        assertNull(UnderlyingNetwork.wireId(null))
        assertNull(UnderlyingNetwork.wireId(0L))
    }
}
