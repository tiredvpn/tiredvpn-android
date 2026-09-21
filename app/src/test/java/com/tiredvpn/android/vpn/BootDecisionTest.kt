package com.tiredvpn.android.vpn

import com.tiredvpn.android.vpn.BootDecision.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the VPN comes back by itself, which until now was decided by a
 * ladder of `if`s inside a BroadcastReceiver and asserted nowhere.
 *
 * The two paths ask different questions and the difference is not an
 * oversight — it is just undocumented. A reboot obeys the `connect_on_boot`
 * setting and ignores whether the tunnel was up; an app update obeys whether
 * the tunnel was up and ignores the setting. Both are pinned below, so the
 * next person to "make them consistent" has to say which way.
 */
class BootDecisionTest {

    private val granted = { true }
    private val configured = { true }

    // --- after a reboot ------------------------------------------------------

    @Test
    fun `a reboot with autostart on and everything in place starts the VPN`() {
        assertEquals(
            Outcome.START,
            BootDecision.afterBoot(connectOnBoot = true, hasValidConfig = configured, hasVpnPermission = granted),
        )
    }

    @Test
    fun `autostart off stops the reboot path`() {
        assertEquals(
            Outcome.AUTOSTART_OFF,
            BootDecision.afterBoot(connectOnBoot = false, hasValidConfig = configured, hasVpnPermission = granted),
        )
    }

    @Test
    fun `a reboot without a usable server does not start`() {
        assertEquals(
            Outcome.NO_VALID_CONFIG,
            BootDecision.afterBoot(connectOnBoot = true, hasValidConfig = { false }, hasVpnPermission = granted),
        )
    }

    @Test
    fun `a reboot without VPN consent does not start`() {
        assertEquals(
            Outcome.NO_VPN_PERMISSION,
            BootDecision.afterBoot(connectOnBoot = true, hasValidConfig = configured, hasVpnPermission = { false }),
        )
    }

    /**
     * The asymmetry, stated. A reboot starts the VPN even though nothing was
     * running when the device went down — the setting is the whole decision.
     * The app-update path below does the opposite with the same inputs.
     */
    @Test
    fun `a reboot ignores whether the tunnel was up before`() {
        assertEquals(
            Outcome.START,
            BootDecision.afterBoot(connectOnBoot = true, hasValidConfig = configured, hasVpnPermission = granted),
        )
        assertEquals(
            Outcome.NOT_RUNNING_BEFORE,
            BootDecision.afterAppUpdate(
                shouldBeConnected = false,
                wasConnected = false,
                hasValidConfig = configured,
                hasVpnPermission = granted,
            ),
        )
    }

    // --- after an app update -------------------------------------------------

    @Test
    fun `an update restores a tunnel that was up`() {
        assertEquals(
            Outcome.START,
            BootDecision.afterAppUpdate(
                shouldBeConnected = true,
                wasConnected = false,
                hasValidConfig = configured,
                hasVpnPermission = granted,
            ),
        )
    }

    /**
     * Either flag is enough. They are written by different parts of the app —
     * `vpn_should_be_connected` by the watchdog, `vpn_was_connected` by the
     * service — and a teardown can clear one without the other.
     */
    @Test
    fun `either flag on its own is enough to restore`() {
        assertEquals(
            Outcome.START,
            BootDecision.afterAppUpdate(
                shouldBeConnected = false,
                wasConnected = true,
                hasValidConfig = configured,
                hasVpnPermission = granted,
            ),
        )
    }

    @Test
    fun `an update leaves a tunnel that was down alone`() {
        assertEquals(
            Outcome.NOT_RUNNING_BEFORE,
            BootDecision.afterAppUpdate(
                shouldBeConnected = false,
                wasConnected = false,
                hasValidConfig = configured,
                hasVpnPermission = granted,
            ),
        )
    }

    @Test
    fun `an update without a usable server does not start`() {
        assertEquals(
            Outcome.NO_VALID_CONFIG,
            BootDecision.afterAppUpdate(
                shouldBeConnected = true,
                wasConnected = true,
                hasValidConfig = { false },
                hasVpnPermission = granted,
            ),
        )
    }

    @Test
    fun `an update without VPN consent does not start`() {
        assertEquals(
            Outcome.NO_VPN_PERMISSION,
            BootDecision.afterAppUpdate(
                shouldBeConnected = true,
                wasConnected = true,
                hasValidConfig = configured,
                hasVpnPermission = { false },
            ),
        )
    }

    // --- the order of the questions, not just their answers ------------------

    /**
     * The gates are suppliers so that a device with auto-start off does no
     * work at all. Reading the active server unlocks an
     * EncryptedSharedPreferences through the Keystore, at boot, on every
     * device that has the app installed.
     */
    @Test
    fun `autostart off asks nothing else`() {
        var configRead = false
        var permissionAsked = false

        BootDecision.afterBoot(
            connectOnBoot = false,
            hasValidConfig = { configRead = true; true },
            hasVpnPermission = { permissionAsked = true; true },
        )

        assertFalse("the encrypted server store was unlocked for nothing", configRead)
        assertFalse("VPN consent was queried for nothing", permissionAsked)
    }

    /**
     * Same for the update path: nothing was running, so nothing is read.
     */
    @Test
    fun `an update that restores nothing asks nothing else`() {
        var configRead = false
        var permissionAsked = false

        BootDecision.afterAppUpdate(
            shouldBeConnected = false,
            wasConnected = false,
            hasValidConfig = { configRead = true; true },
            hasVpnPermission = { permissionAsked = true; true },
        )

        assertFalse(configRead)
        assertFalse(permissionAsked)
    }

    /**
     * And consent is asked for only once there is something to connect to —
     * the config gate comes first on both paths.
     */
    @Test
    fun `an unusable config short-circuits before consent is queried`() {
        var permissionAsked = false

        BootDecision.afterBoot(
            connectOnBoot = true,
            hasValidConfig = { false },
            hasVpnPermission = { permissionAsked = true; true },
        )
        assertFalse(permissionAsked)

        BootDecision.afterAppUpdate(
            shouldBeConnected = true,
            wasConnected = true,
            hasValidConfig = { false },
            hasVpnPermission = { permissionAsked = true; true },
        )
        assertFalse(permissionAsked)
    }

    /**
     * Positive control (rule 2): the suppliers above prove nothing unless the
     * START path really does call both of them.
     */
    @Test
    fun `the start path does read both gates`() {
        var configRead = false
        var permissionAsked = false

        val outcome = BootDecision.afterBoot(
            connectOnBoot = true,
            hasValidConfig = { configRead = true; true },
            hasVpnPermission = { permissionAsked = true; true },
        )

        assertEquals(Outcome.START, outcome)
        assertTrue(configRead)
        assertTrue(permissionAsked)
    }
}
