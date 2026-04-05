package com.tiredvpn.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import com.tiredvpn.android.util.FileLogger
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.TiredVpnService
import com.tiredvpn.android.vpn.VpnState
import com.tiredvpn.android.vpn.VpnWatchdogWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AirplaneModeReceiver handles VPN reconnection after airplane mode is disabled.
 *
 * When airplane mode is enabled:
 * - All network connections are killed
 * - VPN tunnel dies but service may still be "running"
 *
 * When airplane mode is disabled:
 * - Network comes back gradually (WiFi reconnects, cellular activates)
 * - VPN needs to reconnect
 *
 * This receiver triggers reconnection with appropriate delay to allow
 * network to stabilize before attempting VPN connection.
 */
class AirplaneModeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AirplaneModeReceiver"

        // Delay before reconnecting (give network time to stabilize)
        private const val RECONNECT_DELAY_MS = 3000L

        // Maximum delay before giving up on reconnect
        private const val MAX_RECONNECT_DELAY_MS = 30000L

        private var reconnectJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_AIRPLANE_MODE_CHANGED) return

        val isAirplaneModeOn = isAirplaneModeEnabled(context)
        FileLogger.i(TAG, "=== AIRPLANE MODE CHANGED === isOn=$isAirplaneModeOn")

        if (isAirplaneModeOn) {
            // Airplane mode enabled - network will die, nothing to do
            // VPN service will detect network loss via NetworkCallback
            FileLogger.i(TAG, "Airplane mode ON - VPN will handle via NetworkCallback")
            cancelPendingReconnect()
            return
        }

        // Airplane mode disabled - network is coming back
        FileLogger.i(TAG, "Airplane mode OFF - scheduling VPN reconnect")
        scheduleReconnect(context)
    }

    private fun isAirplaneModeEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0
    }

    private fun scheduleReconnect(context: Context) {
        // Cancel any pending reconnect
        cancelPendingReconnect()

        reconnectJob = scope.launch {
            try {
                // Check if VPN should be connected
                val vpnShouldBeConnected = VpnWatchdogWorker.shouldVpnBeConnected(context)
                val currentState = TiredVpnService.state.value

                FileLogger.d(TAG, "Reconnect check: vpnShouldBeConnected=$vpnShouldBeConnected, currentState=$currentState")

                // Only reconnect if VPN was supposed to be connected
                if (!vpnShouldBeConnected) {
                    FileLogger.d(TAG, "VPN should not be connected, skipping reconnect")
                    return@launch
                }

                // Check if already connected or connecting
                if (currentState is VpnState.Connected) {
                    FileLogger.d(TAG, "VPN already connected, skipping")
                    return@launch
                }

                if (currentState is VpnState.Connecting) {
                    FileLogger.d(TAG, "VPN already connecting, skipping")
                    return@launch
                }

                // Wait for network to stabilize
                FileLogger.i(TAG, "Waiting ${RECONNECT_DELAY_MS}ms for network to stabilize...")
                delay(RECONNECT_DELAY_MS)

                // Re-check state after delay
                val stateAfterWait = TiredVpnService.state.value
                if (stateAfterWait is VpnState.Connected || stateAfterWait is VpnState.Connecting) {
                    FileLogger.d(TAG, "VPN connected/connecting during wait, skipping")
                    return@launch
                }

                // Check if we have valid config
                val config = ServerRepository.getActiveServer(context)
                if (config == null || !config.isValid) {
                    FileLogger.w(TAG, "No valid server config, cannot reconnect")
                    return@launch
                }

                // Check VPN permission
                val vpnIntent = VpnService.prepare(context)
                if (vpnIntent != null) {
                    FileLogger.w(TAG, "VPN permission not granted, cannot auto-reconnect")
                    return@launch
                }

                // Start VPN service
                FileLogger.i(TAG, "=== RECONNECTING VPN AFTER AIRPLANE MODE ===")
                startVpnService(context)

            } catch (e: Exception) {
                FileLogger.e(TAG, "Error during airplane mode reconnect", e)
            }
        }
    }

    private fun cancelPendingReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
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
            FileLogger.i(TAG, "VPN service start initiated after airplane mode")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start VPN service", e)
        }
    }
}
