package com.tiredvpn.android.receiver

import com.tiredvpn.android.receiver.BootPolicy.LastSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the two persistent flags say about the session that ended.
 *
 * The receiver read both, logged them and then connected on `connect_on_boot`
 * alone. 1.10.0 had already split "the user stopped it" from "it stopped by
 * itself" on the teardown side (DisconnectPolicy,
 * VpnWatchdogWorker.markUserDisconnect); nothing on the startup side looked.
 *
 * Scope, against BootDecisionTest in the vpn package: this file owns the
 * reading of the flags and the fact that the receiver asks for it instead of
 * open-coding it. What happens next — which gate stops a start, in which order
 * — is BootDecision's, and is asserted there. Deliberately no test here says
 * whether a given LastSession starts the VPN: that answer also depends on
 * `connect_on_boot` and on two gates this object has never heard of, and
 * pinning it in two places is how the two readings drifted apart the last time.
 */
class BootPolicyTest {

    // --- what the flags mean ---

    @Test
    fun `a user disconnect clears both flags and reads as not wanted`() {
        // DisconnectPolicy.clearsBootFlag(USER) -> vpn_was_connected = false
        // DisconnectPolicy.cancelsWatchdog(USER) -> vpn_should_be_connected = false
        //
        // A fresh install lands on this same case from the other direction:
        // neither flag was ever written. Same answer, right for both reasons -
        // VpnService.prepare has no consent to give a device that never
        // connected either.
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

    // --- the call site ---

    @Test
    fun `both receiver paths read the flags through the policy and decide through the ladder`() {
        val text = receiverSource()

        assertEquals(
            "the flags are turned into a LastSession once per path, and nowhere else",
            2,
            Regex("""BootPolicy\.lastSession\(""").findAll(text).count(),
        )
        assertTrue(
            "the boot path must hand the answer to the ladder",
            text.contains("BootDecision.afterBoot("),
        )
        assertTrue(
            "and so must the update path, rather than open-coding the same rule twice",
            text.contains("BootDecision.afterAppUpdate("),
        )
        assertEquals(
            "each path passes the previous session; a path that stopped would " +
                "silently go back to connecting on connect_on_boot alone",
            2,
            Regex("""lastSessionWanted = lastSession == BootPolicy\.LastSession\.WANTED""")
                .findAll(text).count(),
        )
    }

    /**
     * Positive control (rule 2): the scan above is worth nothing if it is
     * reading an empty string, and "no match" is exactly what an empty string
     * produces for every pattern in it.
     */
    @Test
    fun `the scan reads the receiver it asserts about`() {
        val text = receiverSource()

        assertTrue("BootReceiver.kt looks empty", text.length > 4_000)
        assertTrue(text.contains("class BootReceiver : BroadcastReceiver()"))
        assertFalse(
            "a pattern that matches this file's own prose would match anything",
            text.contains("BootPolicy.lastSessionThatNeverExisted("),
        )
    }

    private fun receiverSource(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(
            requireNotNull(dir),
            "src/main/java/com/tiredvpn/android/receiver/BootReceiver.kt",
        ).readText()
    }
}
