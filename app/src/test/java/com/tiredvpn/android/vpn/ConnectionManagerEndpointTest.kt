package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConnectionManager.replacePort], which every port hop would go through, and
 * the state the manager is in when port hopping is off.
 *
 * Worth testing despite the path being unreachable today (see the guards in
 * VpnCoreCallSiteTest): whoever wires port hopping to the core will reach for
 * this function, and it splits on the last colon — right for `[v6]:port`,
 * silently destructive for a bare IPv6 address.
 */
class ConnectionManagerEndpointTest {

    private fun manager(address: String, port: Int = 995, hopping: PortHopperConfigFixture? = null) =
        ConnectionManager(
            VpnConfig(serverAddress = address, serverPort = port, secret = "s"),
            hopping?.build(),
        )

    /** Keeps the porthopping import out of every call. */
    private class PortHopperConfigFixture(
        private val enabled: Boolean,
        private val start: Int = 47000,
        private val end: Int = 47100,
    ) {
        fun build() = com.tiredvpn.android.porthopping.PortHopperConfig(
            enabled = enabled,
            portRangeStart = start,
            portRangeEnd = end,
            hopIntervalMs = 60_000L,
            strategy = com.tiredvpn.android.porthopping.HopStrategy.RANDOM,
            seed = "fixture".toByteArray(Charsets.US_ASCII),
        )
    }

    // --- replacePort ---------------------------------------------------------

    @Test
    fun `a hostname keeps its host and takes the new port`() {
        assertEquals("vpn.example.com:47123", manager("vpn.example.com").replacePort("vpn.example.com:995", 47123))
    }

    @Test
    fun `an IPv4 endpoint keeps its address`() {
        assertEquals("31.44.3.165:47001", manager("31.44.3.165").replacePort("31.44.3.165:995", 47001))
    }

    /**
     * The bracketed form survives, because the last colon is the port
     * separator. This is the shape VpnConfig.serverAddressV6 documents.
     */
    @Test
    fun `a bracketed IPv6 endpoint keeps all of its address`() {
        assertEquals(
            "[2001:470:1f0a:8eb::2]:47001",
            manager("h").replacePort("[2001:470:1f0a:8eb::2]:995", 47001),
        )
    }

    /**
     * The shape that does not survive. A bare IPv6 literal has no port to
     * replace, so the function chops off everything after its last colon and
     * appends the port — turning `2001:db8::1` into `2001:db8::47001`, a
     * different, routable-looking address.
     *
     * Recorded rather than fixed: the caller is unreachable, and changing the
     * split is a behaviour change. The assertion exists so the fix cannot
     * happen silently.
     */
    @Test
    fun `a bare IPv6 literal is silently mangled`() {
        assertEquals("2001:db8::47001", manager("h").replacePort("2001:db8::1", 47001))
    }

    /**
     * An endpoint with no colon at all gets the port appended, which is the
     * documented fallback and the only branch of the `if` that is not the
     * split.
     */
    @Test
    fun `an endpoint with no port gets one appended`() {
        assertEquals("vpn.example.com:47001", manager("h").replacePort("vpn.example.com", 47001))
    }

    @Test
    fun `replacing a port with the same port is a no-op`() {
        assertEquals("vpn.example.com:995", manager("h").replacePort("vpn.example.com:995", 995))
    }

    // --- the manager with hopping off ----------------------------------------

    /**
     * The default. Without a hopper the manager is a pass-through: the
     * endpoint and port are the config's own, untouched.
     */
    @Test
    fun `with hopping off the endpoint is the configured one`() {
        val manager = manager("vpn.example.com", port = 995)

        assertFalse(manager.isPortHoppingEnabled())
        assertEquals("vpn.example.com:995", manager.getCurrentEndpoint())
        assertEquals(995, manager.getCurrentPort())
        assertNull(manager.getPortHopperStats())
        assertEquals(0L, manager.getTimeUntilNextHopMs())
    }

    /**
     * And `forceHop` on a manager with no hopper returns the configured
     * endpoint rather than inventing a port — the branch that would otherwise
     * hand the core an address it was never configured for.
     */
    @Test
    fun `forcing a hop with no hopper changes nothing`() {
        val manager = manager("vpn.example.com", port = 995)

        assertEquals("vpn.example.com:995", manager.forceHop())
    }

    /**
     * Positive control (rule 2): the assertions above say "off" and have to be
     * distinguishable from "the constructor never builds a hopper at all".
     */
    @Test
    fun `with hopping on the endpoint moves into the hop range`() {
        val manager = manager("vpn.example.com", port = 995, hopping = PortHopperConfigFixture(enabled = true))

        assertTrue(manager.isPortHoppingEnabled())
        assertTrue(
            "port ${manager.getCurrentPort()} is outside the configured hop range",
            manager.getCurrentPort() in 47000..47100,
        )
        assertEquals("vpn.example.com:${manager.getCurrentPort()}", manager.getCurrentEndpoint())
    }

    /**
     * A config flagged disabled must not produce a hopper even though one was
     * passed. The `enabled` flag is checked in the constructor, separately
     * from the null check.
     */
    @Test
    fun `a disabled hopping config builds no hopper`() {
        val manager = manager("vpn.example.com", port = 995, hopping = PortHopperConfigFixture(enabled = false))

        assertFalse(manager.isPortHoppingEnabled())
        assertEquals(995, manager.getCurrentPort())
    }

    /**
     * An invalid range is rejected by `PortHopperConfig.validate` and the
     * manager carries on without a hopper rather than throwing during a
     * connect.
     */
    @Test
    fun `an invalid hopping range leaves the manager without a hopper`() {
        val manager = manager(
            "vpn.example.com",
            port = 995,
            hopping = PortHopperConfigFixture(enabled = true, start = 48000, end = 47000),
        )

        assertFalse("an inverted range must not produce a hopper", manager.isPortHoppingEnabled())
        assertEquals(995, manager.getCurrentPort())
    }
}
