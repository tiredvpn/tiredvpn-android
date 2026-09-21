package com.tiredvpn.android.vpn

/**
 * Why the tunnel is being torn down.
 *
 * The distinction exists because [DisconnectPolicy] turns it into writes to
 * persistent flags that outlive the process — `vpn_should_be_connected` and
 * `vpn_was_connected`. VpnWatchdogWorker.doWork and AirplaneModeReceiver gate
 * on them, and one teardown that clears them on a path the user never touched
 * turns a transient failure into "the VPN never comes back until the user taps
 * connect again".
 *
 * BootReceiver is the exception and is deliberately not in that list: it logs
 * both flags and then gates only on the `connect_on_boot` setting, so a reboot
 * starts the VPN whenever that setting is on, whatever the last teardown was.
 * Whether a reboot should honour the last session's state or the setting is a
 * product question, not a bug in this table — but the table does not cover it.
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

/**
 * Whether BootReceiver starts the VPN, and if not, which gate stopped it.
 *
 * The two paths do not ask the same questions, and the difference is the thing
 * worth pinning. After a reboot the `connect_on_boot` setting decides on its
 * own: the VPN comes up whether or not it was running when the device went
 * down. After an app update it is the other way round — the setting is not
 * consulted at all, and the VPN comes back only if it was up before. Both are
 * defensible and neither is written down anywhere except in the order of the
 * `if`s; see the note on BootReceiver in [StopIntent].
 *
 * The last two inputs are suppliers rather than values because their order
 * matters as much as their answers: reading the active server unlocks an
 * EncryptedSharedPreferences, and asking for VPN consent is a binder call.
 * Neither should happen on a device where auto-start is simply off.
 */
internal object BootDecision {

    enum class Outcome {
        /** Every gate passed: start the service and arm the watchdog. */
        START,

        /** `connect_on_boot` is off. Only reachable from the boot path. */
        AUTOSTART_OFF,

        /**
         * Nothing was running before the update, so nothing is restored. Only
         * reachable from the app-update path.
         */
        NOT_RUNNING_BEFORE,

        /** No server, or one that fails [VpnConfig.isValid]. */
        NO_VALID_CONFIG,

        /**
         * VpnService.prepare returned an intent, i.e. our consent is gone.
         * Every start path would fail anyway, so we do not try.
         */
        NO_VPN_PERMISSION,
    }

    fun afterBoot(
        connectOnBoot: Boolean,
        hasValidConfig: () -> Boolean,
        hasVpnPermission: () -> Boolean,
    ): Outcome = when {
        !connectOnBoot -> Outcome.AUTOSTART_OFF
        !hasValidConfig() -> Outcome.NO_VALID_CONFIG
        !hasVpnPermission() -> Outcome.NO_VPN_PERMISSION
        else -> Outcome.START
    }

    fun afterAppUpdate(
        shouldBeConnected: Boolean,
        wasConnected: Boolean,
        hasValidConfig: () -> Boolean,
        hasVpnPermission: () -> Boolean,
    ): Outcome = when {
        !shouldBeConnected && !wasConnected -> Outcome.NOT_RUNNING_BEFORE
        !hasValidConfig() -> Outcome.NO_VALID_CONFIG
        !hasVpnPermission() -> Outcome.NO_VPN_PERMISSION
        else -> Outcome.START
    }
}
