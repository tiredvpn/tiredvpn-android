package com.tiredvpn.android.vpn

import com.tiredvpn.android.porthopping.HopStrategy
import com.tiredvpn.android.porthopping.PortHopper
import com.tiredvpn.android.porthopping.PortHopperConfig
import com.tiredvpn.android.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages VPN connections with port hopping support.
 *
 * Port hopping periodically changes the server port to evade DPI detection.
 * When a hop occurs, the connection is transparently reconnected to the new port.
 */
class ConnectionManager(
    private val config: VpnConfig,
    private val portHoppingConfig: PortHopperConfig? = null
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val HOP_CHECK_INTERVAL_MS = 1000L // Check every second

        /**
         * Create port hopping config from VPN config.
         * Looks for port hopping settings in the config.
         */
        fun createPortHoppingConfig(
            enabled: Boolean = false,
            portRangeStart: Int = 47000,
            portRangeEnd: Int = 65535,
            hopIntervalMs: Long = 60_000L,
            strategy: String = "random",
            seed: ByteArray? = null
        ): PortHopperConfig? {
            if (!enabled) return null

            return PortHopperConfig(
                enabled = true,
                portRangeStart = portRangeStart,
                portRangeEnd = portRangeEnd,
                hopIntervalMs = hopIntervalMs,
                strategy = HopStrategy.fromString(strategy),
                seed = seed
            )
        }
    }

    private var portHopper: PortHopper? = null
    private var hopCheckerJob: Job? = null
    private var reconnectCallback: (suspend (newEndpoint: String) -> Unit)? = null
    private var isActive: Boolean = false

    init {
        // Initialize port hopper if config is provided and enabled
        if (portHoppingConfig != null && portHoppingConfig.enabled) {
            portHoppingConfig.validate().onSuccess {
                portHopper = PortHopper(portHoppingConfig)
                FileLogger.i(TAG, "Port hopping enabled: ${portHoppingConfig.strategy}, range ${portHoppingConfig.portRangeStart}-${portHoppingConfig.portRangeEnd}")
            }.onFailure { e ->
                FileLogger.w(TAG, "Port hopping config invalid: ${e.message}")
            }
        }
    }

    /**
     * Get the current server endpoint with port hopping applied.
     * If port hopping is disabled, returns the original endpoint.
     */
    fun getCurrentEndpoint(): String {
        val hopper = portHopper ?: return config.serverEndpoint
        val port = hopper.currentPort()
        return replacePort(config.serverEndpoint, port)
    }

    /**
     * Get current port (with hopping applied if enabled).
     */
    fun getCurrentPort(): Int {
        return portHopper?.currentPort() ?: config.serverPort
    }

    /**
     * Check if port hopping is enabled and active.
     */
    fun isPortHoppingEnabled(): Boolean {
        return portHopper != null
    }

    /**
     * Get port hopper statistics (null if hopping disabled).
     */
    fun getPortHopperStats() = portHopper?.stats()

    /**
     * Set callback for when reconnect is needed due to port hop.
     */
    fun onReconnectNeeded(callback: suspend (newEndpoint: String) -> Unit) {
        reconnectCallback = callback
    }

    /**
     * Start the hop checker coroutine.
     * This periodically checks if it's time to hop and triggers reconnect.
     */
    fun startHopChecker(scope: CoroutineScope) {
        if (portHopper == null) {
            FileLogger.d(TAG, "Port hopping disabled, not starting hop checker")
            return
        }

        // Cancel any existing checker
        stopHopChecker()

        isActive = true

        hopCheckerJob = scope.launch {
            FileLogger.i(TAG, "Hop checker started")

            while (isActive) {
                delay(HOP_CHECK_INTERVAL_MS)

                if (!isActive) break

                portHopper?.let { hopper ->
                    if (hopper.shouldHop()) {
                        val oldPort = hopper.currentPort()
                        val newPort = hopper.nextPort()

                        FileLogger.i(TAG, "Port hop triggered: $oldPort -> $newPort")

                        // Notify about the hop (callback will handle reconnect)
                        val newEndpoint = replacePort(config.serverEndpoint, newPort)

                        try {
                            withContext(Dispatchers.Main) {
                                reconnectCallback?.invoke(newEndpoint)
                            }
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "Reconnect callback failed: ${e.message}")
                        }
                    }
                }
            }

            FileLogger.i(TAG, "Hop checker stopped")
        }
    }

    /**
     * Stop the hop checker coroutine.
     */
    fun stopHopChecker() {
        isActive = false
        hopCheckerJob?.cancel()
        hopCheckerJob = null
    }

    /**
     * Force an immediate port hop (useful for manual retry).
     * Returns the new endpoint.
     */
    fun forceHop(): String {
        portHopper?.let { hopper ->
            val oldPort = hopper.currentPort()
            val newPort = hopper.nextPort()
            FileLogger.i(TAG, "Forced port hop: $oldPort -> $newPort")
            return replacePort(config.serverEndpoint, newPort)
        }
        return config.serverEndpoint
    }

    /**
     * Reset port hopper to initial state.
     * Useful when reconnecting from scratch.
     */
    fun resetPortHopper() {
        portHopper?.reset()
        FileLogger.d(TAG, "Port hopper reset")
    }

    /**
     * Get time until next hop in milliseconds.
     */
    fun getTimeUntilNextHopMs(): Long {
        return portHopper?.timeUntilNextHopMs() ?: 0
    }

    /**
     * Replace port in host:port string.
     */
    private fun replacePort(endpoint: String, newPort: Int): String {
        val colonIndex = endpoint.lastIndexOf(':')
        return if (colonIndex != -1) {
            val host = endpoint.substring(0, colonIndex)
            "$host:$newPort"
        } else {
            "$endpoint:$newPort"
        }
    }

}
