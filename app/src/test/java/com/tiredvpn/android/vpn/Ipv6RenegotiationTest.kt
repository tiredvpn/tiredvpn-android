package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6RenegotiationTest {

    private fun apply(
        currentIp6: String = "fd00::7",
        currentServerIp6: String = "fd00::1",
        reportedIp6: String? = null,
        reportedServerIp6: String? = null,
        removed: Boolean = false,
    ) = Ipv6Renegotiation.apply(currentIp6, currentServerIp6, reportedIp6, reportedServerIp6, removed)

    /**
     * The distinction the whole object exists for. `ip6` is `omitempty`, so
     * every reconnect of a v4-only tunnel answers without it. Reading that as
     * "the core has no v6" would rebuild the interface on every single
     * reconnect — and since the rebuild makes the core reconnect again, it
     * would not stop.
     */
    @Test
    fun `an absent field is not a report of none`() {
        val outcome = apply(reportedIp6 = null, reportedServerIp6 = null)
        assertFalse(outcome.interfaceMustBeRebuilt)
        assertEquals("fd00::7", outcome.ip6)
        assertEquals("fd00::1", outcome.serverIp6)
    }

    @Test
    fun `an unchanged pair does not rebuild anything`() {
        val outcome = apply(reportedIp6 = "fd00::7", reportedServerIp6 = "fd00::1")
        assertFalse("this is the loop breaker", outcome.interfaceMustBeRebuilt)
    }

    @Test
    fun `a new client address is adopted and rebuilt`() {
        val outcome = apply(reportedIp6 = "fd00::9", reportedServerIp6 = "fd00::1")
        assertTrue(outcome.interfaceMustBeRebuilt)
        assertEquals("fd00::9", outcome.ip6)
        assertEquals("fd00::1", outcome.serverIp6)
    }

    @Test
    fun `a new server address alone is enough to rebuild`() {
        val outcome = apply(reportedIp6 = "fd00::7", reportedServerIp6 = "fd00::2")
        assertTrue(outcome.interfaceMustBeRebuilt)
        assertEquals("fd00::2", outcome.serverIp6)
    }

    /** `ipv6_removed: true` on the reconnect response. */
    @Test
    fun `an explicit removal clears both addresses`() {
        val outcome = apply(removed = true)
        assertTrue(outcome.interfaceMustBeRebuilt)
        assertEquals("", outcome.ip6)
        assertEquals("", outcome.serverIp6)
    }

    @Test
    fun `removal on a session that had no v6 changes nothing`() {
        val outcome = apply(currentIp6 = "", currentServerIp6 = "", removed = true)
        assertFalse(outcome.interfaceMustBeRebuilt)
        assertEquals("", outcome.ip6)
    }

    /**
     * The ipv6_changed event always carries both keys and writes "" for a nil
     * address, and it only fires on a change — so there, unlike in the
     * response, an empty string is removal.
     */
    @Test
    fun `an empty reported address is removal`() {
        val outcome = apply(reportedIp6 = "", reportedServerIp6 = "")
        assertTrue(outcome.interfaceMustBeRebuilt)
        assertEquals("", outcome.ip6)
        assertEquals("", outcome.serverIp6)
    }

    @Test
    fun `gaining v6 on a session that had none is a rebuild`() {
        val outcome = apply(
            currentIp6 = "", currentServerIp6 = "",
            reportedIp6 = "fd00::5", reportedServerIp6 = "fd00::1",
        )
        assertTrue(outcome.interfaceMustBeRebuilt)
        assertEquals("fd00::5", outcome.ip6)
    }

    @Test
    fun `removal wins over a reported pair`() {
        // The core should not send both, but if it does, the explicit teardown
        // flag is the safer reading: a stale address routes v6 into a hole.
        val outcome = apply(reportedIp6 = "fd00::9", reportedServerIp6 = "fd00::2", removed = true)
        assertEquals("", outcome.ip6)
        assertEquals("", outcome.serverIp6)
    }

    /**
     * Applying the outcome and asking again must settle: the rebuild sends
     * network_changed, the core reconnects and answers with a pair, and that
     * second answer must not start another round.
     */
    @Test
    fun `the exchange converges after one rebuild`() {
        val first = apply(reportedIp6 = "fd00::9", reportedServerIp6 = "fd00::2")
        assertTrue(first.interfaceMustBeRebuilt)

        val second = Ipv6Renegotiation.apply(
            currentIp6 = first.ip6,
            currentServerIp6 = first.serverIp6,
            reportedIp6 = "fd00::9",
            reportedServerIp6 = "fd00::2",
            removed = false,
        )
        assertFalse(second.interfaceMustBeRebuilt)
    }
}
