package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTransitionTest {

    @Test
    fun `the three transitions the core names`() {
        assertEquals("wifi_to_lte", NetworkTransition.reason("wifi", "mobile"))
        assertEquals("lte_to_wifi", NetworkTransition.reason("mobile", "wifi"))
        assertEquals("cell_handoff", NetworkTransition.reason("mobile", "mobile"))
    }

    /**
     * Anything else stays unnamed rather than borrowing one of the three: the
     * reason ends up in the core's log and in the `reconnecting` event, so a
     * wrong label is worse than none.
     */
    @Test
    fun `anything else is unnamed`() {
        assertEquals("", NetworkTransition.reason("wifi", "wifi"))
        assertEquals("", NetworkTransition.reason("ethernet", "wifi"))
        assertEquals("", NetworkTransition.reason("wifi", "ethernet"))
        assertEquals("", NetworkTransition.reason("none", "wifi"))
        assertEquals("", NetworkTransition.reason("unknown", "mobile"))
        assertEquals("", NetworkTransition.reason("", ""))
    }

    /**
     * The spellings have to be the ones the active network monitor produces,
     * or every lookup silently falls through to "".
     */
    @Test
    fun `the transport names match the monitor's vocabulary`() {
        assertEquals("wifi", NetworkTransition.WIFI)
        assertEquals("mobile", NetworkTransition.MOBILE)
    }

    /** Only the values internal/tun/control.go documents may be produced. */
    @Test
    fun `no other value can ever be produced`() {
        val vocabulary = listOf("wifi", "mobile", "ethernet", "other", "none", "unknown", "")
        val produced = vocabulary.flatMap { from ->
            vocabulary.map { to -> NetworkTransition.reason(from, to) }
        }.toSet()

        assertEquals(setOf("", "wifi_to_lte", "lte_to_wifi", "cell_handoff"), produced)
    }
}
