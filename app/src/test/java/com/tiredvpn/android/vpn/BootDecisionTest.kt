package com.tiredvpn.android.vpn

import com.tiredvpn.android.vpn.BootDecision.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder of gates an automatic start runs, which until now was a run of
 * `if`s inside a BroadcastReceiver and asserted nowhere.
 *
 * Scope, against BootPolicyTest next door: that file owns what the two
 * persistent flags *mean* and that the receiver asks rather than open-codes
 * them. This one owns what is done with the answer — which gate stops a start,
 * in which order the gates are asked, and what each path refuses to consult.
 * Nothing here reads a flag; `lastSessionWanted` arrives already decided.
 *
 * The two paths differ in exactly one rung. A reboot also obeys
 * `connect_on_boot`; an app update does not consult it, because the user asked
 * for the reboot and did not ask for the update.
 */
class BootDecisionTest {

    private val granted = { true }
    private val configured = { true }

    // --- the rule the whole thing exists for ---------------------------------

    /**
     * Stated once for both paths, because it is one promise to the user and it
     * was broken on the boot path only: `connect_on_boot` defaults to true, so
     * every reboot undid a Disconnect.
     */
    @Test
    fun `a VPN the user switched off is not restarted by a reboot or by an update`() {
        assertEquals(
            Outcome.NOT_RUNNING_BEFORE,
            BootDecision.afterBoot(
                connectOnBoot = true,
                lastSessionWanted = false,
                hasValidConfig = configured,
                hasVpnPermission = granted,
            ),
        )
        assertEquals(
            Outcome.NOT_RUNNING_BEFORE,
            BootDecision.afterAppUpdate(
                lastSessionWanted = false,
                hasValidConfig = configured,
                hasVpnPermission = granted,
            ),
        )
    }

    @Test
    fun `a session that was live when it ended comes back both ways`() {
        assertEquals(
            Outcome.START,
            BootDecision.afterBoot(
                connectOnBoot = true,
                lastSessionWanted = true,
                hasValidConfig = configured,
                hasVpnPermission = granted,
            ),
        )
        assertEquals(
            Outcome.START,
            BootDecision.afterAppUpdate(
                lastSessionWanted = true,
                hasValidConfig = configured,
                hasVpnPermission = granted,
            ),
        )
    }

    // --- the one rung that differs -------------------------------------------

    @Test
    fun `autostart off stops the reboot path even for a session that was live`() {
        assertEquals(
            Outcome.AUTOSTART_OFF,
            BootDecision.afterBoot(
                connectOnBoot = false,
                lastSessionWanted = true,
                hasValidConfig = configured,
                hasVpnPermission = granted,
            ),
        )
    }

    /**
     * The update path cannot consult the setting even by accident: it has no
     * parameter to consult it with. Checked by signature rather than by
     * behaviour, because a behavioural test can only try the values that exist.
     */
    @Test
    fun `an update does not consult connect on boot`() {
        val parameters = BootDecision::class.java.methods
            .single { it.name == "afterAppUpdate" }
            .parameterTypes

        assertEquals(
            "afterAppUpdate takes lastSessionWanted and the two gates, nothing else",
            3,
            parameters.size,
        )
        assertEquals(
            "the one Boolean it takes is the previous session, not a preference",
            1,
            parameters.count { it == Boolean::class.javaPrimitiveType },
        )

        // Positive control (rule 2): counting parameters says nothing unless
        // the same count can tell the two signatures apart. The boot path
        // takes the same three plus connectOnBoot.
        assertEquals(
            4,
            BootDecision::class.java.methods.single { it.name == "afterBoot" }.parameterTypes.size,
        )
    }

    // --- the remaining gates, on both paths ----------------------------------

    @Test
    fun `no usable server stops either path`() {
        assertEquals(
            Outcome.NO_VALID_CONFIG,
            BootDecision.afterBoot(
                connectOnBoot = true,
                lastSessionWanted = true,
                hasValidConfig = { false },
                hasVpnPermission = granted,
            ),
        )
        assertEquals(
            Outcome.NO_VALID_CONFIG,
            BootDecision.afterAppUpdate(
                lastSessionWanted = true,
                hasValidConfig = { false },
                hasVpnPermission = granted,
            ),
        )
    }

    @Test
    fun `no VPN consent stops either path`() {
        assertEquals(
            Outcome.NO_VPN_PERMISSION,
            BootDecision.afterBoot(
                connectOnBoot = true,
                lastSessionWanted = true,
                hasValidConfig = configured,
                hasVpnPermission = { false },
            ),
        )
        assertEquals(
            Outcome.NO_VPN_PERMISSION,
            BootDecision.afterAppUpdate(
                lastSessionWanted = true,
                hasValidConfig = configured,
                hasVpnPermission = { false },
            ),
        )
    }

    // --- the order of the questions, not just their answers ------------------

    /**
     * The gates are suppliers so that a device with auto-start off does no work
     * at all. Reading the active server unlocks an EncryptedSharedPreferences
     * through the Keystore, at boot, on every device that has the app
     * installed; asking for VPN consent is a binder call.
     */
    @Test
    fun `autostart off asks nothing else`() {
        var configRead = false
        var permissionAsked = false

        BootDecision.afterBoot(
            connectOnBoot = false,
            lastSessionWanted = true,
            hasValidConfig = { configRead = true; true },
            hasVpnPermission = { permissionAsked = true; true },
        )

        assertFalse("the encrypted server store was unlocked for nothing", configRead)
        assertFalse("VPN consent was queried for nothing", permissionAsked)
    }

    /** Same for a session the user ended: nothing is going to start, so nothing is read. */
    @Test
    fun `a session the user ended asks nothing else, on either path`() {
        var configRead = false
        var permissionAsked = false
        val watchConfig = { configRead = true; true }
        val watchPermission = { permissionAsked = true; true }

        BootDecision.afterBoot(
            connectOnBoot = true,
            lastSessionWanted = false,
            hasValidConfig = watchConfig,
            hasVpnPermission = watchPermission,
        )
        BootDecision.afterAppUpdate(
            lastSessionWanted = false,
            hasValidConfig = watchConfig,
            hasVpnPermission = watchPermission,
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
            lastSessionWanted = true,
            hasValidConfig = { false },
            hasVpnPermission = { permissionAsked = true; true },
        )
        assertFalse(permissionAsked)

        BootDecision.afterAppUpdate(
            lastSessionWanted = true,
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
            lastSessionWanted = true,
            hasValidConfig = { configRead = true; true },
            hasVpnPermission = { permissionAsked = true; true },
        )

        assertEquals(Outcome.START, outcome)
        assertTrue(configRead)
        assertTrue(permissionAsked)
    }
}
