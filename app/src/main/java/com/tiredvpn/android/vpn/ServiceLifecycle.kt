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
 * BootReceiver is not in that list because it only reads them, but it is the
 * reason the distinctions above have to be exact: it turns the two flags into
 * BootPolicy.LastSession and refuses to raise the tunnel after a reboot when
 * the answer is NOT_WANTED. So a teardown that clears a flag it should have
 * left standing does not just lose a restart — it decides, silently and
 * permanently, that the user wanted the VPN off.
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
 * Both paths ask what the previous session was — a VPN the user switched off
 * stays off, whatever the restart was — and they differ in exactly one rung:
 * a reboot also obeys `connect_on_boot`, an app update does not consult it at
 * all. The user asked for the reboot and did not ask for the update, and an
 * update that silently switches the VPN on for someone who had it off is the
 * same defect from the other side. What the flags mean is BootPolicy's
 * question; see also the note on BootReceiver in [StopIntent].
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

    /**
     * @param connectOnBoot     the user's `connect_on_boot` preference
     * @param lastSessionWanted whether the previous session ended for a reason
     *        other than the user switching the VPN off; see BootPolicy, which
     *        reads that from the two persistent flags. A reboot restores what
     *        was running, not what the setting alone permits.
     */
    fun afterBoot(
        connectOnBoot: Boolean,
        lastSessionWanted: Boolean,
        hasValidConfig: () -> Boolean,
        hasVpnPermission: () -> Boolean,
    ): Outcome = when {
        !connectOnBoot -> Outcome.AUTOSTART_OFF
        !lastSessionWanted -> Outcome.NOT_RUNNING_BEFORE
        !hasValidConfig() -> Outcome.NO_VALID_CONFIG
        !hasVpnPermission() -> Outcome.NO_VPN_PERMISSION
        else -> Outcome.START
    }

    /**
     * An app update is not a boot: it never asks `connect_on_boot`, because the
     * user did not ask for the restart, and an update that silently switches
     * the VPN on for someone who had it off is the boot defect from the other
     * side. Only the previous session decides.
     */
    fun afterAppUpdate(
        lastSessionWanted: Boolean,
        hasValidConfig: () -> Boolean,
        hasVpnPermission: () -> Boolean,
    ): Outcome = when {
        !lastSessionWanted -> Outcome.NOT_RUNNING_BEFORE
        !hasValidConfig() -> Outcome.NO_VALID_CONFIG
        !hasVpnPermission() -> Outcome.NO_VPN_PERMISSION
        else -> Outcome.START
    }
}
