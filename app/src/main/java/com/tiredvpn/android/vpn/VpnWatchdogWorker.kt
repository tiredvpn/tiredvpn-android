package com.tiredvpn.android.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tiredvpn.android.util.FileLogger
import java.util.concurrent.TimeUnit

/**
 * VPN Watchdog Worker - periodically checks VPN health and restarts if needed.
 *
 * This is a critical component for VPN reliability on Android:
 * 1. Android may kill "phantom processes" (our Go binary) without notifying the service
 * 2. NetworkCallback may not fire reliably after long network outages
 * 3. The VPN service may be killed by battery optimization
 *
 * The watchdog runs every 15 minutes and checks:
 * - Is VPN supposed to be connected?
 * - Is VPN actually connected?
 * - Is network available?
 * - If VPN should be connected but isn't, restart it
 */
class VpnWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "VpnWatchdog"
        const val WORK_NAME = "VpnWatchdog"

        // How often to check VPN health (minimum 15 minutes for WorkManager)
        private const val CHECK_INTERVAL_MINUTES = 15L

        // Preferences keys
        private const val PREFS_NAME = "tiredvpn_settings"
        private const val KEY_WATCHDOG_ENABLED = "watchdog_enabled"
        private const val KEY_LAST_VPN_SHOULD_BE_CONNECTED = "vpn_should_be_connected"
        private const val KEY_LAST_CONNECTED_TIME = "last_connected_time"

        /**
         * Schedule the VPN watchdog to run periodically.
         * Should be called when VPN connects successfully.
         */
        fun schedule(context: Context) {
            FileLogger.i(TAG, "Scheduling VPN watchdog (every ${CHECK_INTERVAL_MINUTES}min)")

            // Mark that VPN should be connected
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WATCHDOG_ENABLED, true)
                .putBoolean(KEY_LAST_VPN_SHOULD_BE_CONNECTED, true)
                .putLong(KEY_LAST_CONNECTED_TIME, System.currentTimeMillis())
                .apply()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<VpnWatchdogWorker>(
                CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
                workRequest
            )

            FileLogger.i(TAG, "VPN watchdog scheduled successfully")
        }

        /**
         * Cancel the VPN watchdog.
         * Should be called when user explicitly disconnects VPN.
         */
        fun cancel(context: Context) {
            FileLogger.i(TAG, "Cancelling VPN watchdog")

            // Mark that VPN should NOT be connected
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WATCHDOG_ENABLED, false)
                .putBoolean(KEY_LAST_VPN_SHOULD_BE_CONNECTED, false)
                .apply()

            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Check if VPN should be connected (user hasn't disconnected).
         */
        fun shouldVpnBeConnected(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_LAST_VPN_SHOULD_BE_CONNECTED, false)
        }

        /**
         * Mark that VPN is intentionally disconnecting (user action).
         */
        fun markUserDisconnect(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LAST_VPN_SHOULD_BE_CONNECTED, false)
                .apply()
        }
    }

    override fun doWork(): Result {
        FileLogger.i(TAG, "=== VPN WATCHDOG CHECK ===")

        try {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // 1. Check if watchdog is enabled
            val watchdogEnabled = prefs.getBoolean(KEY_WATCHDOG_ENABLED, false)
            if (!watchdogEnabled) {
                FileLogger.d(TAG, "Watchdog disabled, skipping check")
                return Result.success()
            }

            // 2. Check if VPN should be connected
            val shouldBeConnected = prefs.getBoolean(KEY_LAST_VPN_SHOULD_BE_CONNECTED, false)
            if (!shouldBeConnected) {
                FileLogger.d(TAG, "VPN should not be connected (user disconnected), skipping")
                return Result.success()
            }

            // 3. Check current VPN state
            val currentState = TiredVpnService.state.value
            FileLogger.i(TAG, "Current VPN state: $currentState")

            val isVpnConnected = currentState is VpnState.Connected
            val isVpnConnecting = currentState is VpnState.Connecting

            if (isVpnConnected) {
                FileLogger.d(TAG, "VPN is connected, all good")
                // Update last connected time
                prefs.edit().putLong(KEY_LAST_CONNECTED_TIME, System.currentTimeMillis()).apply()
                return Result.success()
            }

            if (isVpnConnecting) {
                FileLogger.d(TAG, "VPN is connecting, waiting...")
                return Result.success()
            }

            // 4. VPN should be connected but isn't - check network first
            if (!hasNetworkConnectivity()) {
                FileLogger.i(TAG, "No network connectivity, will retry on next check")
                return Result.success()
            }

            // 5. Check if we have VPN permission
            val vpnIntent = VpnService.prepare(applicationContext)
            if (vpnIntent != null) {
                FileLogger.w(TAG, "VPN permission not granted, cannot auto-restart")
                return Result.success()
            }

            // 6. Check if we have valid server config
            val config = ServerRepository.getActiveServer(applicationContext)
            if (config == null || !config.isValid) {
                FileLogger.w(TAG, "No valid server config, cannot auto-restart")
                return Result.success()
            }

            // 7. All checks passed - restart VPN!
            FileLogger.i(TAG, "=== WATCHDOG RESTARTING VPN ===")
            FileLogger.i(TAG, "State: $currentState, Network: available, Config: valid")

            restartVpn()

            return Result.success()

        } catch (e: Exception) {
            FileLogger.e(TAG, "Watchdog check failed", e)
            return Result.failure()
        }
    }

    private fun hasNetworkConnectivity(): Boolean {
        return try {
            val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to check network: ${e.message}")
            false
        }
    }

    private fun restartVpn() {
        try {
            val serviceIntent = Intent(applicationContext, TiredVpnService::class.java).apply {
                action = TiredVpnService.ACTION_CONNECT
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            FileLogger.i(TAG, "VPN restart initiated by watchdog")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to restart VPN", e)
        }
    }
}
