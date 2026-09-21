package com.tiredvpn.android.vpn

/**
 * Why the tunnel is being torn down.
 *
 * The distinction exists because [DisconnectPolicy] turns it into writes to
 * persistent flags that outlive the process — `vpn_should_be_connected` and
 * `vpn_was_connected`. Everything that can bring the tunnel back reads them:
 * VpnWatchdogWorker.doWork, BootReceiver, AirplaneModeReceiver. One teardown
 * that clears them on a path the user never touched turns a transient failure
 * into "the VPN never comes back until the user taps connect again".
 */
internal enum class StopIntent {
    /** The user asked for it: ACTION_DISCONNECT, ACTION_FORCE_RESET. */
    USER,

    /**
     * The system withdrew our VPN consent, or another VPN app took the slot
     * (onRevoke). Not the user's preference, but we must not fight for the
     * slot either — our consent is gone, so every restart path would fail
     * VpnService.prepare() anyway.
     */
    REVOKED,

    /**
     * Our own machinery gave up: reconnect budget exhausted, config turned
     * invalid mid-reconnect, service destroyed. The user still wants a VPN,
     * so nothing persistent may be cleared.
     */
    TECHNICAL,
}

/**
 * What a teardown is allowed to write to storage that survives the process.
 *
 * Read as a table:
 *
 * ```
 *              boot flag    watchdog work   "don't auto-start"
 *   USER        cleared      cancelled       (implied by cancel)
 *   REVOKED     cleared      kept            marked
 *   TECHNICAL   kept         kept            not marked
 * ```
 */
internal object DisconnectPolicy {

    /** Clear BootReceiver's "VPN was connected" marker. */
    fun clearsBootFlag(intent: StopIntent): Boolean = intent != StopIntent.TECHNICAL

    /**
     * Cancel the periodic watchdog outright (also clears
     * `vpn_should_be_connected` and `watchdog_enabled`).
     */
    fun cancelsWatchdog(intent: StopIntent): Boolean = intent == StopIntent.USER

    /**
     * Clear only `vpn_should_be_connected`, leaving the periodic work in
     * place. Used for REVOKED: the machinery stays armed for the next
     * successful connect, which re-marks the flag through
     * VpnWatchdogWorker.schedule, but nothing tries to restart us while our
     * consent is gone.
     */
    fun marksUserDisconnect(intent: StopIntent): Boolean = intent == StopIntent.REVOKED
}

/**
 * What to do with the null intent Android hands a START_STICKY service after
 * it restarts the process.
 *
 * The old code fell through `when (intent?.action)` without a match, so the
 * service came up holding no foreground notification, no tunnel and no
 * stopSelf — alive and useless until the next WorkManager tick, up to 15
 * minutes later. The process can also kill itself here: the connect watchdog
 * calls Process.killProcess when a native call wedges an IO thread.
 */
internal object StickyRestart {

    enum class Decision {
        /** Persistent flags say the tunnel should be up: connect. */
        RECONNECT,

        /** Nothing should be running, or nothing can be: stop the service. */
        STOP,
    }

    fun decide(shouldBeConnected: Boolean, hasValidConfig: Boolean): Decision =
        if (shouldBeConnected && hasValidConfig) Decision.RECONNECT else Decision.STOP
}
