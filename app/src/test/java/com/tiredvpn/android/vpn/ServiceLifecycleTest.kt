package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecycleTest {

    /**
     * The defect: every teardown path went through one `disconnect()` that
     * wrote `vpn_should_be_connected = false`. Reconnect budget exhaustion,
     * an invalid config mid-reconnect, and onDestroy all took it, after which
     * the watchdog, BootReceiver and AirplaneModeReceiver were all gated off
     * and the tunnel could never come back on its own.
     */
    @Test
    fun `a technical teardown writes nothing persistent`() {
        assertFalse(DisconnectPolicy.clearsBootFlag(StopIntent.TECHNICAL))
        assertFalse(DisconnectPolicy.cancelsWatchdog(StopIntent.TECHNICAL))
        assertFalse(DisconnectPolicy.marksUserDisconnect(StopIntent.TECHNICAL))
    }

    /** The user tapping disconnect must stop everything, watchdog included. */
    @Test
    fun `a user teardown clears the boot flag and cancels the watchdog`() {
        assertTrue(DisconnectPolicy.clearsBootFlag(StopIntent.USER))
        assertTrue(DisconnectPolicy.cancelsWatchdog(StopIntent.USER))
        // cancel() already clears vpn_should_be_connected; marking it twice
        // would only hide which call did what.
        assertFalse(DisconnectPolicy.marksUserDisconnect(StopIntent.USER))
    }

    /**
     * Revocation is the in-between case: our VPN consent is gone, so nothing
     * may try to restart us, but the user never asked to turn the VPN off and
     * the periodic watchdog stays armed for the next successful connect.
     */
    @Test
    fun `revocation stops auto-start without cancelling the watchdog`() {
        assertTrue(DisconnectPolicy.clearsBootFlag(StopIntent.REVOKED))
        assertFalse(DisconnectPolicy.cancelsWatchdog(StopIntent.REVOKED))
        assertTrue(DisconnectPolicy.marksUserDisconnect(StopIntent.REVOKED))
    }

    /** No two intents may agree on all three writes, or the enum is decoration. */
    @Test
    fun `each intent has its own combination of writes`() {
        val signatures = StopIntent.entries.map {
            Triple(
                DisconnectPolicy.clearsBootFlag(it),
                DisconnectPolicy.cancelsWatchdog(it),
                DisconnectPolicy.marksUserDisconnect(it),
            )
        }
        assertEquals(StopIntent.entries.size, signatures.toSet().size)
    }

    /**
     * Android restarts a START_STICKY service with a null intent. Doing
     * nothing left the service alive with no notification and no tunnel until
     * the next WorkManager tick, up to 15 minutes later.
     */
    @Test
    fun `a sticky restart reconnects when the flags say the tunnel should be up`() {
        assertEquals(
            StickyRestart.Decision.RECONNECT,
            StickyRestart.decide(shouldBeConnected = true, hasValidConfig = true),
        )
    }

    @Test
    fun `a sticky restart stops the service when nothing should be running`() {
        assertEquals(
            StickyRestart.Decision.STOP,
            StickyRestart.decide(shouldBeConnected = false, hasValidConfig = true),
        )
        // A valid config without the flag is a user who disconnected; a flag
        // without a config is a profile that was deleted. Neither may connect.
        assertEquals(
            StickyRestart.Decision.STOP,
            StickyRestart.decide(shouldBeConnected = true, hasValidConfig = false),
        )
        assertEquals(
            StickyRestart.Decision.STOP,
            StickyRestart.decide(shouldBeConnected = false, hasValidConfig = false),
        )
    }
}
