package com.tiredvpn.android.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * Helper class for managing battery optimization settings.
 *
 * VPN apps need to be excluded from battery optimization (Doze mode) to:
 * 1. Run reliably in the background
 * 2. Maintain persistent connections
 * 3. Reconnect quickly after network changes
 *
 * Without exemption, Android may:
 * - Kill the VPN service during Doze
 * - Delay network access
 * - Prevent auto-reconnect after network loss
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    /**
     * Check if app is excluded from battery optimization.
     * Returns true if:
     * - Running on Android < 6.0 (no Doze)
     * - App is already whitelisted
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true // No Doze on pre-Marshmallow
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Request battery optimization exemption.
     * Shows system dialog asking user to whitelist the app.
     *
     * Note: This uses REQUEST_IGNORE_BATTERY_OPTIMIZATIONS which may not
     * be allowed on Google Play for all apps. VPN apps are usually exempt
     * from this restriction.
     *
     * @return Intent to start, or null if already exempted
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun createBatteryOptimizationIntent(context: Context): Intent? {
        if (isIgnoringBatteryOptimizations(context)) {
            FileLogger.d(TAG, "Already exempted from battery optimization")
            return null
        }

        FileLogger.i(TAG, "Creating battery optimization exemption request")

        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Open battery optimization settings page where user can manually
     * exempt the app. Use this as fallback if direct request fails.
     */
    fun createBatteryOptimizationSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }

    /**
     * Request exemption silently - returns true if dialog was shown.
     * Call this from MainActivity or SettingsActivity.
     */
    fun requestExemptionIfNeeded(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }

        if (isIgnoringBatteryOptimizations(context)) {
            return false
        }

        try {
            val intent = createBatteryOptimizationIntent(context)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                FileLogger.i(TAG, "Battery optimization exemption dialog shown")
                return true
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to show battery optimization dialog", e)
            // Some devices don't support direct exemption request
            // User will need to manually exempt in settings
        }

        return false
    }

    /**
     * Check if we should prompt user for battery optimization exemption.
     * Returns true if:
     * - Not already exempted
     * - Haven't asked recently (to avoid annoying user)
     * - VPN is configured
     */
    fun shouldPromptForExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }

        if (isIgnoringBatteryOptimizations(context)) {
            return false
        }

        // Check if we've asked recently (once per session is enough)
        val prefs = context.getSharedPreferences("tiredvpn_settings", Context.MODE_PRIVATE)
        val lastPromptTime = prefs.getLong("battery_opt_prompt_time", 0)
        val now = System.currentTimeMillis()

        // Don't prompt more than once per day
        if (now - lastPromptTime < 24 * 60 * 60 * 1000) {
            return false
        }

        // Record prompt time
        prefs.edit().putLong("battery_opt_prompt_time", now).apply()

        return true
    }

    /**
     * Get user-friendly message explaining why battery optimization
     * exemption is needed for VPN apps.
     */
    fun getExplanationMessage(): String {
        return "To ensure TiredVPN stays connected reliably:\n\n" +
                "1. Battery optimization must be disabled for this app\n" +
                "2. This allows the VPN to run in the background\n" +
                "3. Auto-reconnect after network changes will work properly\n\n" +
                "Without this exemption, Android may disconnect your VPN " +
                "to save battery."
    }

    /**
     * Get brief status message about battery optimization.
     */
    fun getStatusMessage(context: Context): String {
        return if (isIgnoringBatteryOptimizations(context)) {
            "Battery optimization: Disabled (optimal)"
        } else {
            "Battery optimization: Enabled (may affect VPN reliability)"
        }
    }
}
