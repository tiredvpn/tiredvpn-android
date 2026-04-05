package com.tiredvpn.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tiredvpn.android.vpn.TiredVpnService

/**
 * BroadcastReceiver for controlling VPN via ADB broadcasts.
 *
 * Usage examples:
 *
 * Connect VPN:
 *    adb shell am broadcast -a com.tiredvpn.ACTION_CONNECT \
 *      -n com.tiredvpn.android/.receiver.VpnControlReceiver
 *
 * Disconnect VPN:
 *    adb shell am broadcast -a com.tiredvpn.ACTION_DISCONNECT \
 *      -n com.tiredvpn.android/.receiver.VpnControlReceiver
 *
 * Note: The -n flag (explicit component) is required on Android 8+ due to
 * background execution limits for implicit broadcasts.
 */
class VpnControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONNECT = "com.tiredvpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.tiredvpn.ACTION_DISCONNECT"
        private const val TAG = "VpnControlReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")

        when (intent.action) {
            ACTION_CONNECT -> {
                Log.d(TAG, "Starting VPN service")
                startVpnService(context)
            }
            ACTION_DISCONNECT -> {
                Log.d(TAG, "Stopping VPN service")
                stopVpnService(context)
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
    }

    private fun startVpnService(context: Context) {
        val serviceIntent = Intent(context, TiredVpnService::class.java).apply {
            action = TiredVpnService.ACTION_CONNECT
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Starting foreground service (API >= 26)")
                context.startForegroundService(serviceIntent)
            } else {
                Log.d(TAG, "Starting service (API < 26)")
                context.startService(serviceIntent)
            }
            Log.d(TAG, "VPN service start command sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service", e)
        }
    }

    private fun stopVpnService(context: Context) {
        val serviceIntent = Intent(context, TiredVpnService::class.java).apply {
            action = TiredVpnService.ACTION_DISCONNECT
        }

        try {
            context.startService(serviceIntent)
            Log.d(TAG, "VPN service stop command sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN service", e)
        }
    }
}
