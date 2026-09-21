package com.tiredvpn.android.receiver

/**
 * Whether the tunnel should come back by itself, from the two persistent flags
 * that survive a reboot.
 *
 * `connect_on_boot` used to be the whole decision, which made it mean "raise
 * the VPN every time this device starts, whatever happened last". A user who
 * switched the VPN off and then rebooted got it back, and the setting gave no
 * way to say otherwise short of turning auto-connect off entirely. The rule is
 * now: **raise it if the previous session was not ended by the user.**
 *
 * The evidence already exists and is written by the teardown path, one flag per
 * question (see DisconnectPolicy):
 *
 *  - `vpn_was_connected`, cleared by BootReceiver.markVpnDisconnected for every
 *    stop except TECHNICAL;
 *  - `vpn_should_be_connected`, cleared by VpnWatchdogWorker.cancel on a user
 *    stop and by markUserDisconnect on a revocation.
 *
 * So the four cases fall out without a new flag, and without a migration for
 * anyone upgrading:
 *
 *  - user tapped Disconnect - both cleared - [LastSession.NOT_WANTED];
 *  - consent revoked - both cleared - NOT_WANTED, and `VpnService.prepare`
 *    would refuse us anyway;
 *  - we gave up after a bad hour (TECHNICAL) - `vpn_was_connected` stands -
 *    [LastSession.WANTED], because we gave up and the user did not;
 *  - crash, kill, or reboot while connected - both stand - WANTED.
 *
 * A device that has never had a successful connection reads as NOT_WANTED,
 * which is the same answer for a different reason and the right one: nothing
 * has ever worked here, and `VpnService.prepare` has no consent to hand us.
 */
internal object BootPolicy {

    /** What the persistent flags say about the session that ended. */
    enum class LastSession {
        /** Neither flag stands: the user switched it off, or it never ran. */
        NOT_WANTED,

        /** A session was live when it ended, or we stopped it against our will. */
        WANTED,
    }

    fun lastSession(vpnWasConnected: Boolean, vpnShouldBeConnected: Boolean): LastSession =
        if (vpnWasConnected || vpnShouldBeConnected) LastSession.WANTED else LastSession.NOT_WANTED

    /**
     * @param connectOnBoot the user's `connect_on_boot` preference
     * @param last          what the flags say about the previous session
     */
    fun shouldConnectOnBoot(connectOnBoot: Boolean, last: LastSession): Boolean =
        connectOnBoot && last == LastSession.WANTED

    /**
     * An app update is not a boot: it does not ask about `connect_on_boot`,
     * because the user did not ask for the app to restart and an update that
     * silently turns the VPN on for someone who had it off is the same defect
     * from the other side. Only the previous session decides.
     */
    fun shouldRestartAfterUpdate(last: LastSession): Boolean = last == LastSession.WANTED
}
