package com.tiredvpn.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.tiredvpn.android.util.FileLogger
import com.tiredvpn.android.vpn.BootDecision
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.TiredVpnService
import com.tiredvpn.android.vpn.VpnWatchdogWorker

/**
 * BootReceiver handles auto-starting VPN after various system events:
 *
 * 1. BOOT_COMPLETED - Normal boot (after device unlock)
 * 2. LOCKED_BOOT_COMPLETED - Direct Boot (before device unlock) - Android 7+
 * 3. QUICKBOOT_POWERON - Quick boot on some devices (HTC, Xiaomi, etc)
 * 4. MY_PACKAGE_REPLACED - App updated, restart VPN if was running
 *
 * Key implementation details:
 * - Uses device-protected storage for Direct Boot compatibility
 * - Checks VPN permission before starting (must be granted previously)
 * - Respects user preference for auto-connect
 * - Works with VpnWatchdogWorker for reliable startup
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        // Device-protected storage prefs name (accessible before unlock)
        private const val DEVICE_PREFS_NAME = "tiredvpn_device_settings"

        // Credential-protected storage prefs name (normal prefs, after unlock)
        private const val CREDENTIAL_PREFS_NAME = "tiredvpn_settings"

        // Preference keys
        private const val KEY_CONNECT_ON_BOOT = "connect_on_boot"
        private const val KEY_VPN_WAS_CONNECTED = "vpn_was_connected"
        private const val KEY_LAST_CONNECTED_TIME = "last_connected_time"

        /**
         * Mark that VPN is currently connected.
         * Called by TiredVpnService when connection is established.
         * Saves to both device-protected and credential-protected storage for Direct Boot.
         */
        fun markVpnConnected(context: Context) {
            // Save to credential-protected storage (normal)
            context.getSharedPreferences(CREDENTIAL_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_VPN_WAS_CONNECTED, true)
                .putLong(KEY_LAST_CONNECTED_TIME, System.currentTimeMillis())
                .apply()

            // Also save to device-protected storage for Direct Boot
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_VPN_WAS_CONNECTED, true)
                    .putLong(KEY_LAST_CONNECTED_TIME, System.currentTimeMillis())
                    .apply()
            }

            FileLogger.d(TAG, "Marked VPN as connected (for boot recovery)")
        }

        /**
         * Mark that VPN is disconnected by user.
         * Called by TiredVpnService when user explicitly disconnects.
         */
        fun markVpnDisconnected(context: Context) {
            // Clear from credential-protected storage
            context.getSharedPreferences(CREDENTIAL_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_VPN_WAS_CONNECTED, false)
                .apply()

            // Clear from device-protected storage
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_VPN_WAS_CONNECTED, false)
                    .apply()
            }

            FileLogger.d(TAG, "Marked VPN as disconnected (user action)")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        FileLogger.i(TAG, "=== BOOT EVENT RECEIVED === action=$action")

        when (action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // Direct Boot: the device has booted but the user has not
                // unlocked, so credential-protected storage is not mounted.
                //
                // We deliberately do NOT start the service here. The old code
                // did, reading its "should the VPN be up" flag from
                // device-protected storage and then asking ServerRepository for
                // the profile — which lives in credential-protected storage
                // behind an EncryptedSharedPreferences whose Keystore key is
                // not even available before unlock. It saw an empty store and
                // stopped the service as if the user had configured nothing,
                // which is indistinguishable in the log from a real
                // misconfiguration.
                //
                // Moving the profile to device-protected storage would fix the
                // timing by giving up at-rest protection for the one credential
                // the whole product depends on. Not worth it: ACTION_BOOT_COMPLETED
                // arrives the moment the user unlocks, and VpnWatchdogWorker
                // covers the gap.
                FileLogger.i(TAG, "Direct Boot (LOCKED_BOOT_COMPLETED) - server profile is not readable before unlock, waiting for BOOT_COMPLETED")
            }

            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT" -> {
                // Normal boot completed - device is unlocked
                FileLogger.i(TAG, "Boot completed (after unlock) - $action")
                handleBootEvent(context)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // App was updated - restart VPN if it was running before update
                FileLogger.i(TAG, "App updated (MY_PACKAGE_REPLACED)")
                handleAppUpdated(context)
            }

            else -> {
                FileLogger.w(TAG, "Unknown boot action: $action")
            }
        }
    }

    private fun handleBootEvent(context: Context) {
        // Credential-protected storage: reached only after unlock, which is
        // the only moment the server profile can actually be read.
        val prefs = context.getSharedPreferences(CREDENTIAL_PREFS_NAME, Context.MODE_PRIVATE)

        // Check if user enabled auto-connect on boot (default true for TV devices)
        val connectOnBoot = prefs.getBoolean(KEY_CONNECT_ON_BOOT, true)

        // Both halves of the question, in one place. `connect_on_boot` says
        // whether the user allows a boot to raise the tunnel at all; the two
        // persistent flags say whether the session that ended was theirs to
        // end. The receiver used to read the flags, log them and ignore them,
        // so a user who switched the VPN off and rebooted got it back.
        val vpnShouldBeConnected = VpnWatchdogWorker.shouldVpnBeConnected(context)
        val vpnWasConnected = prefs.getBoolean(KEY_VPN_WAS_CONNECTED, false)
        val lastSession = BootPolicy.lastSession(vpnWasConnected, vpnShouldBeConnected)

        FileLogger.d(TAG, "Boot check: connectOnBoot=$connectOnBoot, vpnShouldBeConnected=$vpnShouldBeConnected, vpnWasConnected=$vpnWasConnected, lastSession=$lastSession")

        when (
            BootDecision.afterBoot(
                connectOnBoot = connectOnBoot,
                lastSessionWanted = lastSession == BootPolicy.LastSession.WANTED,
                hasValidConfig = { ServerRepository.getActiveServer(context)?.isValid == true },
                // VpnService.prepare() returns null if permission is already granted
                hasVpnPermission = { VpnService.prepare(context) == null },
            )
        ) {
            BootDecision.Outcome.AUTOSTART_OFF -> {
                FileLogger.d(TAG, "Connect on boot disabled by user, skipping")
                return
            }

            BootDecision.Outcome.NOT_RUNNING_BEFORE -> {
                FileLogger.i(TAG, "Previous session was ended by the user, not reconnecting after boot")
                return
            }

            BootDecision.Outcome.NO_VALID_CONFIG -> {
                FileLogger.d(TAG, "No valid server configured, skipping auto-connect")
                return
            }

            BootDecision.Outcome.NO_VPN_PERMISSION -> {
                FileLogger.w(TAG, "VPN permission not granted, cannot auto-connect")
                FileLogger.w(TAG, "User must connect VPN manually first to grant permission")
                return
            }

            BootDecision.Outcome.START -> {
                FileLogger.i(TAG, "=== STARTING VPN AFTER BOOT ===")
                startVpnService(context)
            }
        }
    }

    private fun handleAppUpdated(context: Context) {
        val prefs = context.getSharedPreferences(CREDENTIAL_PREFS_NAME, Context.MODE_PRIVATE)

        // Check if VPN was running before app update. Same question as the boot
        // path asks, so it is asked through the same object rather than
        // open-coded twice - the two readings had already been drifting, this
        // one being the stricter.
        val vpnShouldBeConnected = VpnWatchdogWorker.shouldVpnBeConnected(context)
        val vpnWasConnected = prefs.getBoolean(KEY_VPN_WAS_CONNECTED, false)
        val lastSession = BootPolicy.lastSession(vpnWasConnected, vpnShouldBeConnected)

        FileLogger.d(TAG, "App updated: vpnShouldBeConnected=$vpnShouldBeConnected, vpnWasConnected=$vpnWasConnected, lastSession=$lastSession")

        when (
            BootDecision.afterAppUpdate(
                lastSessionWanted = lastSession == BootPolicy.LastSession.WANTED,
                hasValidConfig = { ServerRepository.getActiveServer(context)?.isValid == true },
                hasVpnPermission = { VpnService.prepare(context) == null },
            )
        ) {
            BootDecision.Outcome.AUTOSTART_OFF -> return

            BootDecision.Outcome.NOT_RUNNING_BEFORE -> {
                FileLogger.d(TAG, "VPN was not connected before update, skipping")
                return
            }

            BootDecision.Outcome.NO_VALID_CONFIG -> {
                FileLogger.d(TAG, "No valid server configured, skipping")
                return
            }

            BootDecision.Outcome.NO_VPN_PERMISSION -> {
                FileLogger.w(TAG, "VPN permission not granted after update")
                return
            }

            BootDecision.Outcome.START -> {
                FileLogger.i(TAG, "=== RESTARTING VPN AFTER APP UPDATE ===")
                startVpnService(context)
            }
        }
    }

    private fun startVpnService(context: Context) {
        val serviceIntent = Intent(context, TiredVpnService::class.java).apply {
            action = TiredVpnService.ACTION_CONNECT
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            FileLogger.i(TAG, "VPN service start initiated")

            // Schedule watchdog to ensure VPN stays running
            VpnWatchdogWorker.schedule(context)

        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                // BOOT_COMPLETED does carry an FGS allowance, so this is not
                // expected here - but if it happens, the watchdog is the
                // fallback and must be armed.
                FileLogger.w(TAG, "Foreground start refused after boot; arming the watchdog instead")
                VpnWatchdogWorker.schedule(context)
                return
            }
            FileLogger.e(TAG, "Failed to start VPN service", e)
        }
    }
}
