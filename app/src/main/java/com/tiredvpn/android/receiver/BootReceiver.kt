package com.tiredvpn.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.tiredvpn.android.util.FileLogger
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
                // Direct Boot - device just booted, user hasn't unlocked yet
                // Use device-protected storage which is available before unlock
                FileLogger.i(TAG, "Direct Boot (LOCKED_BOOT_COMPLETED) - before unlock")
                handleBootEvent(context, isDirectBoot = true)
            }

            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT" -> {
                // Normal boot completed - device is unlocked
                FileLogger.i(TAG, "Boot completed (after unlock) - $action")
                handleBootEvent(context, isDirectBoot = false)
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

    private fun handleBootEvent(context: Context, isDirectBoot: Boolean) {
        // Get preferences from appropriate storage
        val prefs = if (isDirectBoot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use device-protected storage for Direct Boot
            val deviceContext = context.createDeviceProtectedStorageContext()
            deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
        } else {
            // Use normal credential-protected storage
            context.getSharedPreferences(CREDENTIAL_PREFS_NAME, Context.MODE_PRIVATE)
        }

        // Check if user enabled auto-connect on boot (default true for TV devices)
        val connectOnBoot = prefs.getBoolean(KEY_CONNECT_ON_BOOT, true)

        if (!connectOnBoot) {
            FileLogger.d(TAG, "Connect on boot disabled by user, skipping")
            return
        }

        // Check if VPN should be connected (was running before reboot)
        val vpnShouldBeConnected = VpnWatchdogWorker.shouldVpnBeConnected(context)
        val vpnWasConnected = prefs.getBoolean(KEY_VPN_WAS_CONNECTED, false)

        FileLogger.d(TAG, "Boot check: connectOnBoot=$connectOnBoot, vpnShouldBeConnected=$vpnShouldBeConnected, vpnWasConnected=$vpnWasConnected")

        // In Direct Boot mode, we can only start if VPN was already configured
        // (we can't access credential-protected storage to check config)
        if (isDirectBoot) {
            if (!vpnWasConnected) {
                FileLogger.d(TAG, "Direct Boot: VPN was not connected before, skipping")
                return
            }
            // In Direct Boot, we'll try to start and let the service handle config loading
            FileLogger.i(TAG, "Direct Boot: VPN was connected, attempting restart")
        }

        // For normal boot, check if we have valid config
        if (!isDirectBoot) {
            val config = ServerRepository.getActiveServer(context)
            if (config == null || !config.isValid) {
                FileLogger.d(TAG, "No valid server configured, skipping auto-connect")
                return
            }
        }

        // Check if VPN permission is already granted
        // VpnService.prepare() returns null if permission is already granted
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            FileLogger.w(TAG, "VPN permission not granted, cannot auto-connect")
            FileLogger.w(TAG, "User must connect VPN manually first to grant permission")
            return
        }

        FileLogger.i(TAG, "=== STARTING VPN AFTER BOOT ===")
        startVpnService(context)
    }

    private fun handleAppUpdated(context: Context) {
        val prefs = context.getSharedPreferences(CREDENTIAL_PREFS_NAME, Context.MODE_PRIVATE)

        // Check if VPN was running before app update
        val vpnShouldBeConnected = VpnWatchdogWorker.shouldVpnBeConnected(context)
        val vpnWasConnected = prefs.getBoolean(KEY_VPN_WAS_CONNECTED, false)

        FileLogger.d(TAG, "App updated: vpnShouldBeConnected=$vpnShouldBeConnected, vpnWasConnected=$vpnWasConnected")

        if (!vpnShouldBeConnected && !vpnWasConnected) {
            FileLogger.d(TAG, "VPN was not connected before update, skipping")
            return
        }

        // Check config
        val config = ServerRepository.getActiveServer(context)
        if (config == null || !config.isValid) {
            FileLogger.d(TAG, "No valid server configured, skipping")
            return
        }

        // Check VPN permission
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            FileLogger.w(TAG, "VPN permission not granted after update")
            return
        }

        FileLogger.i(TAG, "=== RESTARTING VPN AFTER APP UPDATE ===")
        startVpnService(context)
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
            FileLogger.e(TAG, "Failed to start VPN service", e)
        }
    }
}
