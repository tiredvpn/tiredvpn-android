package com.tiredvpn.android.vpn

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The decisions behind the in-app kill switch. Where the service asks for
 * them is pinned in VpnCoreCallSiteTest; this pins the answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KillSwitchTest {

    // --- giving up ------------------------------------------------------------

    @Test
    fun `off, the retry loop gives up past the ceiling and not before`() {
        assertFalse(KillSwitch.givesUp(ReconnectBackoff.MAX_ATTEMPTS, holding = false))
        assertTrue(KillSwitch.givesUp(ReconnectBackoff.MAX_ATTEMPTS + 1, holding = false))
    }

    @Test
    fun `on, the retry loop never gives up`() {
        for (attempt in listOf(1, ReconnectBackoff.MAX_ATTEMPTS, ReconnectBackoff.MAX_ATTEMPTS + 1, 10_000)) {
            assertFalse("attempt $attempt", KillSwitch.givesUp(attempt, holding = true))
        }
    }

    // --- what a failed attempt looks like ---------------------------------------

    @Test
    fun `a failed attempt while the switch holds an interface is a reconnect`() {
        assertEquals(VpnState.Connecting, KillSwitch.stateAfterFailedAttempt("timed out", holding = true, interfaceHeld = true))
    }

    @Test
    fun `everything else is reported as the error it is`() {
        for ((holding, held) in listOf(false to true, true to false, false to false)) {
            assertEquals(
                "holding=$holding interfaceHeld=$held",
                VpnState.Error("timed out"),
                KillSwitch.stateAfterFailedAttempt("timed out", holding = holding, interfaceHeld = held),
            )
        }
    }

    // --- the system setting as the service last saw it --------------------------

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("tiredvpn_settings", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test
    fun `nothing recorded reads as unknown, not as off`() {
        assertNull(SystemVpnMode.last(prefs()))
    }

    @Test
    fun `a recording comes back as written, each flag on its own`() {
        val p = prefs()
        SystemVpnMode.record(p, alwaysOn = true, lockdown = false, nowMs = 1_000L)
        assertEquals(SystemVpnMode.Reading(alwaysOn = true, lockdown = false, readAtMs = 1_000L), SystemVpnMode.last(p))
        SystemVpnMode.record(p, alwaysOn = false, lockdown = true, nowMs = 2_000L)
        assertEquals(SystemVpnMode.Reading(alwaysOn = false, lockdown = true, readAtMs = 2_000L), SystemVpnMode.last(p))
    }
}
