package com.tiredvpn.android.receiver

import com.tiredvpn.android.receiver.BootPolicy.LastSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A VPN the user switched off stays off across a reboot.
 *
 * The receiver read both persistent flags, logged them and then connected on
 * `connect_on_boot` alone. 1.10.0 had already split "the user stopped it" from
 * "it stopped by itself" on the teardown side (DisconnectPolicy,
 * VpnWatchdogWorker.markUserDisconnect); nothing on the startup side looked.
 */
class BootPolicyTest {

    // --- what the flags mean ---

    @Test
    fun `a user disconnect clears both flags and reads as not wanted`() {
        // DisconnectPolicy.clearsBootFlag(USER) -> vpn_was_connected = false
        // DisconnectPolicy.cancelsWatchdog(USER) -> vpn_should_be_connected = false
        assertEquals(
            LastSession.NOT_WANTED,
            BootPolicy.lastSession(vpnWasConnected = false, vpnShouldBeConnected = false),
        )
    }

    @Test
    fun `giving up after repeated failures is not the user switching it off`() {
        // StopIntent.TECHNICAL leaves vpn_was_connected standing on purpose:
        // we gave up, the user did not.
        assertEquals(
            LastSession.WANTED,
            BootPolicy.lastSession(vpnWasConnected = true, vpnShouldBeConnected = false),
        )
    }

    @Test
    fun `a crash or a reboot while connected leaves both flags standing`() {
        assertEquals(
            LastSession.WANTED,
            BootPolicy.lastSession(vpnWasConnected = true, vpnShouldBeConnected = true),
        )
    }

    @Test
    fun `the watchdog flag alone is enough`() {
        // The service was killed between schedule() and markVpnConnected().
        assertEquals(
            LastSession.WANTED,
            BootPolicy.lastSession(vpnWasConnected = false, vpnShouldBeConnected = true),
        )
    }

    // --- boot ---

    @Test
    fun `a VPN the user switched off does not come back after a reboot`() {
        assertFalse(
            "this is the defect: connect_on_boot defaults to true, so every reboot " +
                "undid the user's Disconnect",
            BootPolicy.shouldConnectOnBoot(connectOnBoot = true, last = LastSession.NOT_WANTED),
        )
    }

    @Test
    fun `a VPN that was up when the device went down comes back`() {
        assertTrue(BootPolicy.shouldConnectOnBoot(connectOnBoot = true, last = LastSession.WANTED))
    }

    @Test
    fun `connect on boot switched off wins over everything`() {
        assertFalse(BootPolicy.shouldConnectOnBoot(connectOnBoot = false, last = LastSession.WANTED))
        assertFalse(BootPolicy.shouldConnectOnBoot(connectOnBoot = false, last = LastSession.NOT_WANTED))
    }

    @Test
    fun `a first install does not auto-connect`() {
        // Nothing has ever been connected here, so neither flag was ever
        // written. Same answer as a user disconnect, for a different reason,
        // and right either way: VpnService.prepare has no consent to give us.
        val fresh = BootPolicy.lastSession(vpnWasConnected = false, vpnShouldBeConnected = false)
        assertFalse(BootPolicy.shouldConnectOnBoot(connectOnBoot = true, last = fresh))
    }

    // --- app update ---

    @Test
    fun `an app update restarts only what was running`() {
        assertTrue(BootPolicy.shouldRestartAfterUpdate(LastSession.WANTED))
        assertFalse(BootPolicy.shouldRestartAfterUpdate(LastSession.NOT_WANTED))
    }

    @Test
    fun `an app update does not consult connect on boot`() {
        // The user did not ask for the app to restart. Turning the VPN on for
        // someone who had it off, just because they took an update, is the
        // same defect seen from the other side - so the update path has no
        // connectOnBoot parameter at all and cannot grow one by accident.
        assertEquals(
            1,
            BootPolicy::class.java.methods
                .single { it.name == "shouldRestartAfterUpdate" }
                .parameterCount,
        )
    }

    // --- the call site ---

    @Test
    fun `the receiver decides through the policy instead of reading the flags itself`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        val text = File(
            requireNotNull(dir),
            "src/main/java/com/tiredvpn/android/receiver/BootReceiver.kt",
        ).readText()

        assertTrue(
            "the boot path must ask",
            text.contains("BootPolicy.shouldConnectOnBoot(connectOnBoot, lastSession)"),
        )
        assertTrue(
            "and so must the update path, rather than open-coding the same rule twice",
            text.contains("BootPolicy.shouldRestartAfterUpdate(lastSession)"),
        )
        assertFalse(
            "returning on connectOnBoot alone is what let a reboot undo a Disconnect",
            text.contains("if (!connectOnBoot) {\n            FileLogger"),
        )
    }
}
