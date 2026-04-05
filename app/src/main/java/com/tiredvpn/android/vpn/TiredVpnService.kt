package com.tiredvpn.android.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tiredvpn.android.util.FileLogger
import com.tiredvpn.android.R
import com.tiredvpn.android.TiredVpnApp
import com.tiredvpn.android.native.NativeProcess
import com.tiredvpn.android.native.NativeProcessJNI
import com.tiredvpn.android.native.TiredVpnProcess
import com.tiredvpn.android.porthopping.HopStrategy
import com.tiredvpn.android.porthopping.PortHopperConfig
import com.tiredvpn.android.receiver.BootReceiver
import com.tiredvpn.android.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.EmptyCoroutineContext
import org.json.JSONObject
import java.io.File
import java.net.InetAddress

class TiredVpnService : VpnService() {

    companion object {
        private const val TAG = "TiredVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CONTROL_SOCKET_TIMEOUT = 30_000 // 30 sec for connection
        private const val CONNECTION_TIMEOUT = 30_000L // 30 sec max for full connection

        private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        const val ACTION_CONNECT = "com.tiredvpn.android.CONNECT"
        const val ACTION_DISCONNECT = "com.tiredvpn.android.DISCONNECT"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        FileLogger.e(TAG, "UNCAUGHT coroutine exception", throwable)
    }

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private fun ensureScopeActive() {
        if (!scope.isActive) {
            FileLogger.w(TAG, "Coroutine scope is dead, recreating")
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
        }
    }

    /**
     * Cancel and recreate the coroutine scope to release any blocked Dispatchers.IO threads.
     * Must be called after all coroutine jobs are cancelled and resources are cleaned up.
     */
    private fun resetScope() {
        FileLogger.d(TAG, "Resetting coroutine scope (releasing blocked IO threads)")
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tiredvpnProcess: TiredVpnProcess? = null  // Can be NativeProcess or NativeProcessJNI
    private var controlSocket: LocalSocket? = null
    private var statusMonitorJob: Job? = null
    private var protectServerJob: Job? = null  // Socket protection server
    private var connectionJob: Job? = null  // Current connection attempt - can be cancelled
    private var connectAttemptsSinceBodyEntered = 0  // Track if coroutine body ever starts
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionManager: ConnectionManager? = null  // Port hopping manager

    // Connection info from Go binary response
    private var connectedStrategy: String = ""
    private var connectedLatencyMs: Long = 0
    private var connectedAttempts: Int = 1
    private var currentVpnIp: String = ""  // Current assigned VPN IP

    // Network monitoring - ignore events right after connection
    private var connectionTime: Long = 0
    private var lastNetworkChangeTime: Long = 0

    // Reconnection tracking
    private var reconnectAttempts: Int = 0
    private var lastReconnectTime: Long = 0
    private val reconnectCooldownMs = 60_000L // Reset counter after 1 minute of stable connection
    private val maxBackoffMs = 10_000L // Maximum backoff delay (reduced from 30s)
    private var isNetworkLost: Boolean = false // Track if we lost network (for fast reconnect)
    private var networkRecoveryJob: Job? = null // Periodic network check when network is lost
    private var lastNetworkAvailableTime: Long = 0 // Track when we last had network

    // ITERATION 2: Reconnect state management to prevent race conditions
    private val reconnectMutex = kotlinx.coroutines.sync.Mutex()
    private var pendingReconnectJob: Job? = null // Track pending reconnect job to cancel it
    @Volatile private var isReconnecting = false // Atomic flag to prevent parallel reconnects
    private var lastNetworkSignalSentTime: Long = 0 // Debounce for network_available signals to Go

    // Keepalive tracking for dead tunnel detection
    private var lastKeepaliveTime: Long = 0
    private val keepaliveTimeoutMs = 45_000L // Consider dead if no keepalive for 45 seconds

    // Internal process watchdog - faster than WorkManager (which has 15min minimum)
    private var processWatchdogJob: Job? = null
    private val processWatchdogIntervalMs = 30_000L // Check every 30 seconds

    // ITERATION 3: Network availability watchdog and health checks
    private var networkWatchdogJob: Job? = null // Watchdog for long disconnects
    private val networkWatchdogTimeoutMs = 45_000L // Force restart if offline >45s
    private var healthCheckJob: Job? = null // Periodic health check of Go process
    private val healthCheckIntervalMs = 10_000L // Check every 10 seconds

    // PIXEL FIX: Aggressive active network monitoring (backup for unreliable NetworkCallback)
    private var activeNetworkMonitorJob: Job? = null
    private val activeNetworkMonitorIntervalMs = 2_000L // Check every 2 seconds

    // BUG FIX 1: Track last link properties to avoid unnecessary reconnects
    private var lastLinkProperties: String = "" // Serialized IP addresses

    override fun onCreate() {
        super.onCreate()
        FileLogger.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.d(TAG, "onStartCommand: action=${intent?.action}, startId=$startId, scopeActive=${scope.isActive}")
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = ServerRepository.getActiveServer(this)
                FileLogger.d(TAG, "onStartCommand: config=${if (config != null) "present, valid=${config.isValid}" else "null"}")
                if (config != null && config.isValid) {
                    startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                    connect(config)
                } else {
                    FileLogger.e(TAG, "Invalid config")
                    _state.value = VpnState.Error("Invalid configuration")
                    stopSelf()
                }
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
        }
        return START_STICKY
    }

    /**
     * Initialize ConnectionManager with port hopping configuration.
     */
    private fun initConnectionManager(config: VpnConfig) {
        // Stop any existing hop checker
        connectionManager?.stopHopChecker()

        // Create port hopping config if enabled
        val portHopConfig = if (config.portHoppingEnabled) {
            // Convert hex seed string to bytes if provided
            val seedBytes = config.portHopSeed?.let { seedHex ->
                try {
                    // Use hex string as ASCII bytes (same as Go)
                    seedHex.toByteArray(Charsets.US_ASCII)
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Failed to parse port hop seed: ${e.message}")
                    null
                }
            }

            PortHopperConfig(
                enabled = true,
                portRangeStart = config.portHopRangeStart,
                portRangeEnd = config.portHopRangeEnd,
                hopIntervalMs = config.portHopIntervalMs,
                strategy = HopStrategy.fromString(config.portHopStrategy),
                seed = seedBytes
            )
        } else {
            null
        }

        connectionManager = ConnectionManager(config, portHopConfig)

        // Set up reconnect callback for port hops
        connectionManager?.onReconnectNeeded { newEndpoint ->
            FileLogger.i(TAG, "Port hop triggered reconnect to: $newEndpoint")
            handlePortHopReconnect(newEndpoint)
        }
    }

    /**
     * Handle reconnection after port hop.
     * This performs a graceful reconnect without losing VPN state.
     */
    private suspend fun handlePortHopReconnect(newEndpoint: String) {
        val currentState = _state.value
        if (currentState !is VpnState.Connected) {
            FileLogger.w(TAG, "Port hop reconnect skipped - not connected")
            return
        }

        FileLogger.i(TAG, "=== PORT HOP RECONNECT START === endpoint=$newEndpoint")

        // Update notification
        val portMatch = Regex(":(\\d+)$").find(newEndpoint)
        val port = portMatch?.groupValues?.get(1)?.toIntOrNull()
        if (port != null) {
            updateNotification("Hopping to port $port...")
        }

        // Send reconnect command to Go binary with new endpoint
        try {
            val writer = controlSocket?.outputStream?.bufferedWriter()
            if (writer != null) {
                val cmd = JSONObject().apply {
                    put("command", "port_hop")
                    put("new_endpoint", newEndpoint)
                }.toString()

                writer.write(cmd)
                writer.newLine()
                writer.flush()

                FileLogger.i(TAG, "Sent port_hop command: $newEndpoint")

                // Update state with new port
                _state.value = currentState.copy(
                    currentPort = port,
                    portHoppingEnabled = true
                )
                updateNotification("Connected (port $port)")
            } else {
                FileLogger.w(TAG, "Port hop: control socket not available, triggering full reconnect")
                handleControlSocketBroken()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Port hop reconnect failed: ${e.message}")
            // Fall back to full reconnect
            handleControlSocketBroken()
        }
    }

    private fun connect(config: VpnConfig) {
        // Cancel any existing connection attempt
        connectionJob?.cancel()

        // Ensure coroutine scope is alive
        ensureScopeActive()

        // Acquire WakeLock to prevent Android from killing the service
        acquireWakeLock()

        // Initialize ConnectionManager with port hopping if enabled
        initConnectionManager(config)

        // Auto-reset scope if coroutine body hasn't started after 2 attempts
        connectAttemptsSinceBodyEntered++
        if (connectAttemptsSinceBodyEntered > 2) {
            FileLogger.w(TAG, "connect: coroutine body hasn't started in ${connectAttemptsSinceBodyEntered} attempts, resetting scope")
            Log.w(TAG, "connect: coroutine body hasn't started in ${connectAttemptsSinceBodyEntered} attempts, resetting scope")
            resetScope()
            connectAttemptsSinceBodyEntered = 1
        }

        FileLogger.d(TAG, "connect: launching coroutine (scopeActive=${scope.isActive}, scopeJob=${scope.coroutineContext[kotlinx.coroutines.Job]})")

        // Diagnostic: test if scope can dispatch at all
        scope.launch {
            Log.d(TAG, ">>> SCOPE DISPATCH TEST OK on thread=${Thread.currentThread().name}")
            FileLogger.d(TAG, ">>> SCOPE DISPATCH TEST OK on thread=${Thread.currentThread().name}")
        }

        connectionJob = scope.launch {
            connectAttemptsSinceBodyEntered = 0  // Reset counter — body is executing
            Log.d(TAG, ">>> COROUTINE BODY ENTERED on thread=${Thread.currentThread().name}")
            FileLogger.d(TAG, ">>> COROUTINE BODY ENTERED on thread=${Thread.currentThread().name}")
            try {
                FileLogger.i(TAG, "=== CONNECT START === mode=${config.connectionMode}, portHopping=${config.portHoppingEnabled}")
                _state.value = VpnState.Connecting

                // Overall connection timeout - 30 seconds max (REDUCED from 60s for faster failure detection)
                withTimeout(CONNECTION_TIMEOUT) {
                    when (config.connectionMode) {
                        "proxy" -> connectProxyMode(config)
                        else -> connectTunMode(config)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                FileLogger.e(TAG, "Connection timed out after ${CONNECTION_TIMEOUT/1000} seconds")
                _state.value = VpnState.Error("Connection timed out")
                cleanupFailedConnection()
                // Schedule auto-reconnect after timeout
                scheduleAutoReconnect(config)
            } catch (e: CancellationException) {
                FileLogger.i(TAG, "Connection cancelled")
                // Don't set error state - this is intentional cancellation
                return@launch
            } catch (e: Exception) {
                FileLogger.e(TAG, "Connection failed", e)
                _state.value = VpnState.Error(e.message ?: "Unknown error")
                cleanupFailedConnection()
                // Schedule auto-reconnect after failure
                scheduleAutoReconnect(config)
            }
        }
        FileLogger.d(TAG, "connect: job launched, isActive=${connectionJob?.isActive}, isCancelled=${connectionJob?.isCancelled}, isCompleted=${connectionJob?.isCompleted}")
    }

    /**
     * Schedule an automatic reconnection attempt after a failed connection.
     * Uses exponential backoff to avoid hammering the server.
     *
     * ITERATION 2: Now uses mutex to prevent parallel reconnect attempts
     * and cancels pending jobs before scheduling new one.
     *
     * ITERATION 2.1 (P0 FIX): Uses tryLock() and NonCancellable context to fix
     * JobCancellationException race conditions.
     */
    private fun scheduleAutoReconnect(config: VpnConfig) {
        FileLogger.d(TAG, "scheduleAutoReconnect: Called (state=${_state.value})")

        // Don't auto-reconnect if user disconnected
        if (_state.value is VpnState.Disconnected) {
            FileLogger.d(TAG, "scheduleAutoReconnect: State is Disconnected, not reconnecting")
            return
        }

        // CRITICAL: Try to acquire mutex lock - skip if already locked
        if (!reconnectMutex.tryLock()) {
            FileLogger.w(TAG, "scheduleAutoReconnect: Reconnect already in progress (mutex locked), skipping")
            return
        }

        // NOTE: mutex is released INSIDE the coroutine, not here
        FileLogger.d(TAG, "scheduleAutoReconnect: Mutex acquired, proceeding")

        // Cancel any pending reconnect job BEFORE scheduling new one
        pendingReconnectJob?.cancel()

        reconnectAttempts++

        // Maximum number of reconnect attempts before giving up
        val maxReconnectAttempts = 30
        if (reconnectAttempts > maxReconnectAttempts) {
            FileLogger.e(TAG, "scheduleAutoReconnect: Too many attempts ($reconnectAttempts), giving up")
            _state.value = VpnState.Error("Connection failed after $reconnectAttempts attempts")
            reconnectMutex.unlock()
            disconnect()
            return
        }

        // Exponential backoff: 2s, 4s, 6s, 8s, 10s (capped)
        val backoffMs = minOf(2000L * reconnectAttempts, maxBackoffMs)

        FileLogger.i(TAG, "scheduleAutoReconnect: Scheduling reconnect in ${backoffMs}ms (attempt $reconnectAttempts)")

        // Don't overwrite Connected state — new instance may have already connected
        if (_state.value !is VpnState.Connected) {
            _state.value = VpnState.Connecting
        }

        ensureScopeActive()
        pendingReconnectJob = scope.launch {
            try {
                // Wrap delay() to suppress cosmetic JobCancellationException
                try {
                    delay(backoffMs)
                } catch (e: CancellationException) {
                    FileLogger.d(TAG, "scheduleAutoReconnect: Reconnect delay cancelled")
                    return@launch
                }

                if (_state.value is VpnState.Disconnected || _state.value is VpnState.Connected) {
                    FileLogger.d(TAG, "scheduleAutoReconnect: State is ${_state.value}, aborting scheduled reconnect")
                    return@launch
                }

                FileLogger.i(TAG, "scheduleAutoReconnect: Starting reconnect (attempt $reconnectAttempts)")

                withContext(NonCancellable) {
                    isReconnecting = true
                    try {
                        connect(config)
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "scheduleAutoReconnect: connect() failed", e)
                        throw e
                    } finally {
                        isReconnecting = false
                    }
                }
            } catch (e: CancellationException) {
                FileLogger.w(TAG, "scheduleAutoReconnect: Reconnect job cancelled", e)
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "scheduleAutoReconnect: Reconnect job failed", e)
            } finally {
                reconnectMutex.unlock()
                FileLogger.d(TAG, "scheduleAutoReconnect: Mutex released (coroutine done)")
            }
        }
    }

    /**
     * Clean up resources after a failed connection attempt.
     * Does NOT change state or stop service - caller should handle that.
     */
    private fun cleanupFailedConnection() {
        FileLogger.d(TAG, "Cleaning up failed connection...")

        // Stop native process
        tiredvpnProcess?.stop()
        tiredvpnProcess = null

        // Close control socket
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null

        // Stop protect server
        stopProtectServer()

        // Close VPN interface
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        // Clean up socket files
        File("${filesDir.absolutePath}/control.sock").delete()
        File("${filesDir.absolutePath}/protect.sock").delete()

        // Release WakeLock
        releaseWakeLock()
    }

    private suspend fun connectTunMode(config: VpnConfig) {
        val controlPath = "${filesDir.absolutePath}/control.sock"
        val protectPath = "${filesDir.absolutePath}/protect.sock"
        FileLogger.d(TAG, "Control socket path: $controlPath")
        FileLogger.d(TAG, "Protect socket path: $protectPath")

        // Clean up old sockets
        File(controlPath).delete()
        File(protectPath).delete()
        FileLogger.d(TAG, "Old sockets deleted")

        // 0. CRITICAL: Resolve DNS BEFORE starting VPN
        // After VPN is established, DNS queries go through the tunnel (which doesn't work yet)
        FileLogger.i(TAG, "STEP 0: Pre-resolving server hostname...")
        val resolvedEndpoint = resolveServerEndpoint(config.serverEndpoint)
        if (resolvedEndpoint == null) {
            throw RuntimeException("Failed to resolve server hostname: ${config.serverEndpoint}")
        }
        FileLogger.i(TAG, "STEP 0: Resolved ${config.serverEndpoint} -> $resolvedEndpoint")

        // 1a. Start protect socket server BEFORE starting native process
        // Native process will use this to call VpnService.protect() on its sockets
        FileLogger.i(TAG, "STEP 1a: Starting protect socket server...")
        startProtectServer(protectPath)
        FileLogger.i(TAG, "STEP 1a: Protect server started")

        // 1b. Start tiredvpn with control socket (using resolved IP)
        FileLogger.i(TAG, "STEP 1b: Starting tiredvpn process...")
        startTiredVpnProcess(config, resolvedEndpoint, controlPath, protectPath)
        FileLogger.i(TAG, "STEP 1b: Process started")

        // 2. Wait for socket and connect
        FileLogger.i(TAG, "STEP 2: Connecting to control socket...")
        val tunConfig = connectToControlSocket(controlPath)
            ?: throw RuntimeException("Failed to get tunnel config from server")

        FileLogger.i(TAG, "STEP 2: Got tunnel config: IP=${tunConfig.ip}, DNS=${tunConfig.dns}, MTU=${tunConfig.mtu}")

        // 3. Create VPN interface with server-provided config
        FileLogger.i(TAG, "STEP 3: Creating VPN interface...")
        val vpnFd = establishVpn(config, tunConfig)
            ?: throw RuntimeException("Failed to establish VPN interface")
        vpnInterface = vpnFd
        FileLogger.i(TAG, "STEP 3: VPN interface created, fd=${vpnFd.fd}")

        // Diagnostic: check if our VPN is now the active network
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNet = cm.activeNetwork
        val activeCaps = activeNet?.let { cm.getNetworkCapabilities(it) }
        val isVpnTransport = activeCaps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        FileLogger.i(TAG, "STEP 3: Post-establish check: activeNetwork=$activeNet, isVpnTransport=$isVpnTransport")

        // 4. Send fd to tiredvpn via control socket with retry
        // BUG FIX 2: Retry if handshake fails (EOF error from server)
        var tunFd = vpnFd.fd
        var finalIp: String? = null
        val maxHandshakeRetries = 2
        var handshakeAttempt = 0

        while (finalIp == null && handshakeAttempt < maxHandshakeRetries) {
            handshakeAttempt++
            FileLogger.i(TAG, "STEP 4: Sending TUN fd=$tunFd to tiredvpn (attempt $handshakeAttempt/$maxHandshakeRetries)...")
            finalIp = sendTunFd(tunFd)

            if (finalIp == null && handshakeAttempt < maxHandshakeRetries) {
                FileLogger.w(TAG, "STEP 4: Handshake failed, retrying after 1s delay...")
                delay(1000) // Wait 1 second before retry
            }
        }

        if (finalIp == null) {
            FileLogger.e(TAG, "STEP 4: FAILED - sendTunFd failed after $maxHandshakeRetries attempts")
            throw RuntimeException("Failed to activate tunnel after $maxHandshakeRetries attempts")
        }
        FileLogger.i(TAG, "STEP 4: SUCCESS - finalIp=$finalIp (after $handshakeAttempt attempts)")

        // 5. If server assigned different IP, recreate VPN interface
        // FIX: Don't recreate if original IP was "auto" - just use assigned IP
        if (finalIp != tunConfig.ip && finalIp.isNotEmpty() && tunConfig.ip != "auto") {
            FileLogger.w(TAG, "Server assigned different IP: $finalIp (requested: ${tunConfig.ip}), recreating interface")

            val newTunConfig = tunConfig.copy(ip = finalIp)
            val newVpnFd = establishVpn(config, newTunConfig)
            if (newVpnFd == null) {
                FileLogger.e(TAG, "Failed to recreate VPN interface with new IP")
            } else {
                vpnInterface = newVpnFd
                tunFd = newVpnFd.fd

                val newFinalIp = sendTunFd(tunFd)
                if (newFinalIp != null) {
                    finalIp = newFinalIp
                    FileLogger.i(TAG, "VPN interface recreated with IP: $finalIp")
                }
            }
        }

        FileLogger.i(TAG, "STEP 5: Connection complete, updating state...")
        _state.value = VpnState.Connected(
            strategy = connectedStrategy.ifEmpty { config.strategy },
            latencyMs = connectedLatencyMs,
            attempts = connectedAttempts
        )
        updateNotification("Connected • $finalIp")
        currentVpnIp = finalIp
        FileLogger.i(TAG, "=== VPN CONNECTED === strategy=$connectedStrategy, latency=${connectedLatencyMs}ms, ip=$finalIp")

        // Record connection time and reset reconnect counter
        connectionTime = System.currentTimeMillis()
        lastKeepaliveTime = System.currentTimeMillis() // Initialize for health check
        reconnectAttempts = 0

        // Mark VPN as connected for boot recovery (Direct Boot support)
        BootReceiver.markVpnConnected(this)

        // Schedule VPN watchdog to auto-restart if service gets killed
        VpnWatchdogWorker.schedule(this)

        // 6. Start status monitoring
        FileLogger.i(TAG, "STEP 6: Starting status monitoring...")
        startStatusMonitoring()

        // 7. Start network change monitoring
        FileLogger.i(TAG, "STEP 7: Starting network monitoring...")
        startNetworkMonitoring()

        // 8. Start internal process watchdog (detects PhantomProcess kills quickly)
        FileLogger.i(TAG, "STEP 8: Starting process watchdog...")
        startProcessWatchdog()

        // 9. ITERATION 3: Start health check (pings Go process every 10s)
        FileLogger.i(TAG, "STEP 9: Starting health check...")
        startHealthCheck()

        // 10. PIXEL FIX: Start active network monitor (backup for unreliable NetworkCallback)
        FileLogger.i(TAG, "STEP 10: Starting active network monitor (Pixel fix)...")
        startActiveNetworkMonitor()

        FileLogger.i(TAG, "=== ALL STEPS COMPLETE ===")
    }

    private suspend fun connectProxyMode(config: VpnConfig) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw RuntimeException("HTTP Proxy mode requires Android 10+")
        }

        FileLogger.i(TAG, "=== PROXY MODE CONNECT START ===")

        // 1. Start tiredvpn in proxy mode (without -tun, without control socket)
        FileLogger.i(TAG, "STEP 1: Starting tiredvpn in proxy mode...")
        startTiredVpnProxyProcess(config)

        // 2. Wait for proxy port to be ready
        FileLogger.i(TAG, "STEP 2: Waiting for proxy port ${config.proxyPort} to be ready...")
        val proxyReady = waitForProxyPort(config.proxyPort)
        if (!proxyReady) {
            throw RuntimeException("Failed to start HTTP proxy on port ${config.proxyPort}")
        }
        FileLogger.i(TAG, "STEP 2: Proxy is listening on port ${config.proxyPort}")

        // 3. Create VPN with HTTP proxy (Android 10+)
        FileLogger.i(TAG, "STEP 3: Creating VPN with HTTP proxy...")
        val vpnFd = establishProxyVpn(config)
        if (vpnFd == null) {
            throw RuntimeException("Failed to establish VPN interface for proxy")
        }
        vpnInterface = vpnFd

        // 4. Update state
        val proxyAddress = "127.0.0.1:${config.proxyPort}"
        FileLogger.i(TAG, "STEP 4: Proxy mode connected at $proxyAddress")

        _state.value = VpnState.Connected(
            strategy = connectedStrategy.ifEmpty { config.strategy },
            latencyMs = connectedLatencyMs,
            attempts = connectedAttempts,
            mode = "proxy",
            proxyAddress = proxyAddress
        )
        updateNotification("Proxy • $proxyAddress")

        // Record connection time
        connectionTime = System.currentTimeMillis()
        lastKeepaliveTime = System.currentTimeMillis()
        reconnectAttempts = 0

        // Mark VPN as connected for boot recovery (Direct Boot support)
        BootReceiver.markVpnConnected(this)

        // Schedule VPN watchdog to auto-restart if service gets killed
        VpnWatchdogWorker.schedule(this)

        // 5. Start process monitoring (no control socket in proxy mode)
        FileLogger.i(TAG, "STEP 5: Starting process monitoring...")
        startProxyMonitoring()
        startNetworkMonitoring()

        // 6. Start internal process watchdog (detects PhantomProcess kills quickly)
        FileLogger.i(TAG, "STEP 6: Starting process watchdog...")
        startProcessWatchdog()

        // 7. ITERATION 3: Start health check
        FileLogger.i(TAG, "STEP 7: Starting health check...")
        startHealthCheck()

        // 8. PIXEL FIX: Start active network monitor
        FileLogger.i(TAG, "STEP 8: Starting active network monitor (Pixel fix)...")
        startActiveNetworkMonitor()

        FileLogger.i(TAG, "=== PROXY MODE CONNECTED ===")
    }

    private fun startTiredVpnProxyProcess(config: VpnConfig) {
        val binaryPath = "${applicationInfo.nativeLibraryDir}/libtiredvpn.so"
        val binaryFile = File(binaryPath)

        if (!binaryFile.exists()) {
            throw RuntimeException("tiredvpn binary not found at $binaryPath")
        }

        // CRITICAL: Kill any orphan tiredvpn processes BEFORE starting new one
        NativeProcess.killAllTiredVpnProcesses(applicationInfo.nativeLibraryDir)

        // Proxy mode: no -tun, no -control-socket, just -listen
        val args = mutableListOf(
            binaryFile.absolutePath,
            "client",
            "-server", config.serverEndpoint,
            "-secret", config.secret,
            "-listen", "127.0.0.1:${config.proxyPort}"
        )

        // Strategy
        if (config.strategy != "auto") {
            args.addAll(listOf("-strategy", config.strategy))
        }

        // QUIC
        if (config.enableQuic) {
            args.add("-quic")
            args.addAll(listOf("-quic-port", config.quicPort.toString()))
        }

        // Cover host for traffic morphing
        if (config.coverHost.isNotBlank()) {
            args.addAll(listOf("-cover", config.coverHost))
        }

        // RTT masking
        if (config.rttMasking) {
            args.add("-rtt-masking")
            args.addAll(listOf("-rtt-profile", config.rttProfile))
        }

        // Fallback
        if (config.fallbackEnabled) {
            args.add("-fallback")
        }

        // Debug logging controlled by settings toggle
        if (config.debugLogging) args.add("-debug")

        FileLogger.d(TAG, "Starting tiredvpn (proxy mode): ${args.joinToString(" ") {
            if (it == config.secret) "***" else it
        }}")

        // Use JNI mode on all Android versions to avoid SELinux restrictions
        // Android 10+ blocks execution of standalone binaries from app storage
        tiredvpnProcess = if (true) {
            FileLogger.i(TAG, "Using JNI mode (Android ${Build.VERSION.SDK_INT})")
            NativeProcessJNI(
                args = args.drop(1), // Skip binary path for JNI mode
                onOutput = { line ->
                    FileLogger.d(TAG, "[tiredvpn-jni] $line")
                    parseConnectionInfo(line)
                },
                onError = { line ->
                    FileLogger.e(TAG, "[tiredvpn-jni] $line")
                    parseConnectionInfo(line)
                },
                onExit = { code ->
                    FileLogger.w(TAG, "tiredvpn-jni (proxy) exited with code $code")
                    val currentState = _state.value
                    FileLogger.d(TAG, "onExit (proxy-jni): currentState=$currentState")
                    when (currentState) {
                        is VpnState.Disconnected -> {
                            // User disconnected - don't reconnect
                            FileLogger.d(TAG, "onExit (proxy-jni): User disconnected, not reconnecting")
                        }
                        is VpnState.Connecting -> {
                            FileLogger.e(TAG, "onExit (proxy-jni): Process died during Connecting")
                            val config = ServerRepository.getActiveServer(this@TiredVpnService)
                            if (config != null && config.isValid) {
                                FileLogger.i(TAG, "onExit (proxy-jni): Scheduling fast reconnect")
                                scope.launch {
                                    delay(1000)
                                    if (_state.value !is VpnState.Disconnected) {
                                        scheduleAutoReconnect(config)
                                    }
                                }
                            }
                        }
                        is VpnState.Connected -> {
                            scope.launch {
                                FileLogger.e(TAG, "tiredvpn-jni proxy process died while connected, attempting reconnect...")
                                handleControlSocketBroken()
                            }
                        }
                        is VpnState.Error -> {
                            val config = ServerRepository.getActiveServer(this@TiredVpnService)
                            if (config != null && config.isValid) {
                                FileLogger.i(TAG, "onExit (proxy-jni): Scheduling reconnect after error")
                                scope.launch {
                                    delay(2000)
                                    if (_state.value !is VpnState.Disconnected) {
                                        scheduleAutoReconnect(config)
                                    }
                                }
                            }
                        }
                    }  // Close when statement
                }  // Close onExit lambda
            )  // Close NativeProcessJNI constructor
        }  // Close if block
        else {  // Start else block
            NativeProcess(
                args = args,
                onOutput = { line ->
                    FileLogger.d(TAG, "[tiredvpn] $line")
                    parseConnectionInfo(line)
                },
                onError = { line ->
                    FileLogger.e(TAG, "[tiredvpn] $line")
                    parseConnectionInfo(line)
                },
                onExit = { code ->
                    FileLogger.w(TAG, "tiredvpn (proxy) exited with code $code")
                    val currentState = _state.value
                    FileLogger.d(TAG, "onExit (proxy): currentState=$currentState")
                when (currentState) {
                    is VpnState.Disconnected -> {
                        // User disconnected - don't reconnect
                        FileLogger.d(TAG, "onExit (proxy): User disconnected, not reconnecting")
                    }
                    is VpnState.Connecting -> {
                        // Process died during connection - likely killed by PhantomProcess killer
                        FileLogger.e(TAG, "onExit (proxy): Process died during Connecting - likely PhantomProcess kill")
                        val config = ServerRepository.getActiveServer(this@TiredVpnService)
                        if (config != null && config.isValid) {
                            FileLogger.i(TAG, "onExit (proxy): Scheduling fast reconnect after PhantomProcess kill")
                            scope.launch {
                                delay(1000)
                                if (_state.value !is VpnState.Disconnected) {
                                    scheduleAutoReconnect(config)
                                }
                            }
                        }
                    }
                    is VpnState.Connected -> {
                        // Process died while connected - try to reconnect
                        scope.launch {
                            FileLogger.e(TAG, "tiredvpn proxy process died while connected, attempting reconnect...")
                            handleControlSocketBroken()
                        }
                    }
                    is VpnState.Error -> {
                        // Process exited after error - ensure reconnect is scheduled
                        val config = ServerRepository.getActiveServer(this@TiredVpnService)
                        if (config != null && config.isValid) {
                            FileLogger.d(TAG, "onExit (proxy): Error state - ensuring reconnect is scheduled")
                            scope.launch {
                                delay(500)
                                if (_state.value !is VpnState.Disconnected && _state.value !is VpnState.Connecting) {
                                    scheduleAutoReconnect(config)
                                }
                            }
                        }
                    }
                }  // Close when
            }  // Close onExit lambda
            )  // Close NativeProcess constructor
        }.also { it.start() }
    }

    /**
     * Wait for proxy port to be listening by trying to connect to it.
     */
    private suspend fun waitForProxyPort(port: Int): Boolean {
        FileLogger.d(TAG, "waitForProxyPort: waiting for port $port to be ready")

        val deadline = System.currentTimeMillis() + CONTROL_SOCKET_TIMEOUT
        var waitMs = 0L

        while (System.currentTimeMillis() < deadline) {
            // Check if process is still alive
            if (tiredvpnProcess?.isRunning == true != true) {
                FileLogger.e(TAG, "waitForProxyPort: tiredvpn process died")
                return false
            }

            // Try to connect to the proxy port
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
                socket.close()
                FileLogger.d(TAG, "waitForProxyPort: port $port is ready after ${waitMs}ms")
                return true
            } catch (e: Exception) {
                // Port not ready yet, keep waiting
                if (waitMs % 1000 == 0L) {
                    FileLogger.d(TAG, "waitForProxyPort: still waiting... ${waitMs}ms")
                }
            }

            delay(200)
            waitMs += 200
        }

        FileLogger.e(TAG, "waitForProxyPort: timeout waiting for port $port")
        return false
    }

    /**
     * Monitor tiredvpn process in proxy mode (no control socket).
     */
    private fun startProxyMonitoring() {
        statusMonitorJob = scope.launch {
            // Just monitor if process is still running
            while (isActive && _state.value is VpnState.Connected) {
                delay(5000) // Check every 5 seconds

                if (tiredvpnProcess?.isRunning == true != true) {
                    FileLogger.e(TAG, "Proxy process died, reconnecting...")
                    handleControlSocketBroken()
                    break
                }
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun establishProxyVpn(config: VpnConfig): ParcelFileDescriptor? {
        return try {
            val proxyInfo = ProxyInfo.buildDirectProxy("127.0.0.1", config.proxyPort)

            val builder = Builder()
                .setSession("TiredVPN Proxy")
                .setMtu(1500)
                .addAddress("10.255.255.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setHttpProxy(proxyInfo)

            // Apply split tunneling
            val usesAllowedApps = applySplitTunneling(builder)

            // Exclude our own app if not using allowedApps mode
            if (!usesAllowedApps) {
                builder.addDisallowedApplication(packageName)
            }

            // Apply kill switch
            applyKillSwitch(builder)

            builder.establish()
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to establish proxy VPN interface", e)
            null
        }
    }

    /**
     * Start the socket protection server.
     * Go side sends fd as 4-byte little-endian int, we call VpnService.protect(fd).
     * Returns 0 on success, 1 on failure.
     * Uses FILESYSTEM namespace (not abstract) so Go can connect to it.
     */
    private fun startProtectServer(socketPath: String) {
        // Cancel any existing protect server
        protectServerJob?.cancel()

        // Remove old socket file
        File(socketPath).delete()

        protectServerJob = scope.launch {
            var serverSocket: LocalSocket? = null
            try {
                // Create socket and bind to FILESYSTEM namespace
                serverSocket = LocalSocket(LocalSocket.SOCKET_STREAM)
                serverSocket.bind(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))

                // Make socket file accessible
                File(socketPath).setReadable(true, false)
                File(socketPath).setWritable(true, false)

                // Get the file descriptor and start listening
                val fd = serverSocket.fileDescriptor
                android.system.Os.listen(fd, 5)

                FileLogger.d(TAG, "Protect server listening on $socketPath (FILESYSTEM namespace)")

                while (isActive) {
                    try {
                        // Accept connection using Os API
                        val clientFd = android.system.Os.accept(fd, null)

                        launch {
                            try {
                                handleProtectClientFd(clientFd)
                            } catch (e: Exception) {
                                FileLogger.w(TAG, "Protect client error", e)
                            } finally {
                                try { android.system.Os.close(clientFd) } catch (_: Exception) {}
                            }
                        }
                    } catch (e: android.system.ErrnoException) {
                        if (isActive && e.errno != android.system.OsConstants.EINTR) {
                            FileLogger.w(TAG, "Protect accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Protect server failed", e)
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                File(socketPath).delete()
            }
        }
    }

    /**
     * Handle a single protect client connection using raw file descriptor.
     * Protocol: Read 4-byte little-endian fd, call protect(), send 1-byte response (0=ok, 1=fail).
     * Note: Go's InitAndroidProtector() does a test connection (connect+close) without sending data.
     */
    private fun handleProtectClientFd(clientFd: java.io.FileDescriptor) {
        // Read fd as 4-byte little-endian int
        val fdBytes = ByteArray(4)
        val byteBuf = java.nio.ByteBuffer.wrap(fdBytes)
        var totalRead = 0
        while (totalRead < 4) {
            val n = android.system.Os.read(clientFd, byteBuf)
            if (n <= 0) {
                // 0-byte read means client closed connection (Go's test connection)
                if (totalRead == 0) {
                    FileLogger.d(TAG, "Protect: test connection (no data)")
                } else {
                    FileLogger.w(TAG, "Protect: incomplete read, got $totalRead/4 bytes")
                }
                return  // Don't send error for test connections
            }
            totalRead += n
        }

        // Little-endian to int
        val fd = (fdBytes[0].toInt() and 0xFF) or
                 ((fdBytes[1].toInt() and 0xFF) shl 8) or
                 ((fdBytes[2].toInt() and 0xFF) shl 16) or
                 ((fdBytes[3].toInt() and 0xFF) shl 24)

        // Call VpnService.protect()
        val success = protect(fd)
        if (success) {
            FileLogger.d(TAG, "Protected fd=$fd")
            android.system.Os.write(clientFd, java.nio.ByteBuffer.wrap(byteArrayOf(0)))
        } else {
            FileLogger.w(TAG, "Failed to protect fd=$fd")
            android.system.Os.write(clientFd, java.nio.ByteBuffer.wrap(byteArrayOf(1)))
        }
    }

    private fun stopProtectServer() {
        protectServerJob?.cancel()
        protectServerJob = null
    }

    /**
     * Resolve server hostname to IP address BEFORE VPN is established.
     * This is critical because after VPN is up, DNS queries go through the tunnel.
     * Returns "ip:port" or null on failure.
     */
    private suspend fun resolveServerEndpoint(endpoint: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Parse host:port
                val parts = endpoint.split(":")
                if (parts.size != 2) {
                    FileLogger.e(TAG, "Invalid endpoint format: $endpoint")
                    return@withContext null
                }
                val host = parts[0]
                val port = parts[1]

                // Check if already an IP address
                if (host.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                    FileLogger.d(TAG, "Endpoint is already an IP: $endpoint")
                    return@withContext endpoint
                }

                // Resolve hostname to IP
                FileLogger.d(TAG, "Resolving hostname: $host")
                val addresses = InetAddress.getAllByName(host)
                if (addresses.isEmpty()) {
                    FileLogger.e(TAG, "No addresses found for: $host")
                    return@withContext null
                }

                // Prefer IPv4
                val ipv4 = addresses.find { it is java.net.Inet4Address }
                val ip = (ipv4 ?: addresses[0]).hostAddress

                FileLogger.i(TAG, "Resolved $host -> $ip")
                "$ip:$port"
            } catch (e: Exception) {
                FileLogger.e(TAG, "DNS resolution failed for $endpoint", e)
                null
            }
        }
    }

    /**
     * Extract tiredvpn binary from assets to a location where it can be executed.
     * Android 10+ prevents execution from app's filesDir due to W^X policy.
     * We use codeCacheDir which allows execution.
     * Returns the path to the extracted binary.
     */
    private fun extractTiredVpnBinary(): File {
        // Use codeCacheDir instead of filesDir - it allows executable code
        val binaryFile = File(codeCacheDir, "tiredvpn")

        // Only extract if binary doesn't exist or assets version is newer
        if (!binaryFile.exists()) {
            FileLogger.i(TAG, "Extracting tiredvpn binary from assets...")
            val assetName = when (Build.CPU_ABI) {
                "arm64-v8a" -> "tiredvpn-arm64"
                "armeabi-v7a" -> "tiredvpn-arm32"
                "x86_64" -> "tiredvpn-x86_64"
                else -> "tiredvpn-arm64"  // Default to arm64
            }

            assets.open(assetName).use { input ->
                binaryFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Make executable - use multiple methods for better compatibility
            try {
                // Method 1: Java API
                binaryFile.setExecutable(true, false)
                binaryFile.setReadable(true, false)

                // Method 2: Try chmod via Runtime (more reliable on some devices)
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", binaryFile.absolutePath)).waitFor()
                    FileLogger.i(TAG, "Set permissions via chmod 755")
                } catch (e: Exception) {
                    FileLogger.w(TAG, "chmod via Runtime failed: ${e.message}")
                }

                // Verify permissions were set
                val canExecute = binaryFile.canExecute()
                FileLogger.i(TAG, "Binary extracted, canExecute=$canExecute, path=${binaryFile.absolutePath}")

                if (!canExecute) {
                    FileLogger.e(TAG, "WARNING: Binary may not be executable!")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error setting executable permissions", e)
            }
        } else {
            // Binary already exists, ensure it's still executable
            if (!binaryFile.canExecute()) {
                FileLogger.w(TAG, "Binary exists but not executable, fixing permissions...")
                try {
                    binaryFile.setExecutable(true, false)
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", binaryFile.absolutePath)).waitFor()
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to fix permissions", e)
                }
            }
        }

        return binaryFile
    }

    private fun startTiredVpnProcess(config: VpnConfig, serverEndpoint: String, controlPath: String, protectPath: String) {
        val binaryFile = extractTiredVpnBinary()

        if (!binaryFile.exists()) {
            throw RuntimeException("tiredvpn binary not found at ${binaryFile.absolutePath}")
        }

        // Verify binary is executable before attempting to run
        if (!binaryFile.canExecute()) {
            FileLogger.e(TAG, "Binary is not executable! Attempting emergency chmod...")
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", binaryFile.absolutePath)).waitFor()
                if (!binaryFile.canExecute()) {
                    throw RuntimeException("Failed to make binary executable: ${binaryFile.absolutePath}")
                }
                FileLogger.i(TAG, "Emergency chmod successful")
            } catch (e: Exception) {
                throw RuntimeException("Binary at ${binaryFile.absolutePath} is not executable and cannot be fixed", e)
            }
        }

        FileLogger.i(TAG, "Binary verified executable: ${binaryFile.absolutePath}")

        // CRITICAL: Kill any orphan tiredvpn processes BEFORE starting new one
        // This prevents process leaks during reconnects
        NativeProcess.killAllTiredVpnProcesses(filesDir.absolutePath)

        val args = mutableListOf(
            binaryFile.absolutePath,
            "client",
            "-server", serverEndpoint,  // Use pre-resolved IP:port
            "-secret", config.secret,
            "-control-socket", controlPath,
            "-protect-path", protectPath,  // Socket protection for VpnService.protect()
            "-tun",
            "-tun-ip", "auto"
        )

        // Strategy
        if (config.strategy != "auto") {
            args.addAll(listOf("-strategy", config.strategy))
        }

        // QUIC
        if (config.enableQuic) {
            args.add("-quic")
            args.addAll(listOf("-quic-port", config.quicPort.toString()))
        }

        // Cover host for traffic morphing
        if (config.coverHost.isNotBlank()) {
            args.addAll(listOf("-cover", config.coverHost))
        }

        // RTT masking
        if (config.rttMasking) {
            args.add("-rtt-masking")
            args.addAll(listOf("-rtt-profile", config.rttProfile))
        }

        // Fallback
        if (config.fallbackEnabled) {
            args.add("-fallback")
        }

        // Android mode - disables os/exec, ICMP checks (causes SIGSYS)
        args.add("-android")

        // Debug logging controlled by settings toggle (significant per-packet overhead)
        if (config.debugLogging) args.add("-debug")

        FileLogger.d(TAG, "Starting tiredvpn: ${args.joinToString(" ") {
            if (it == config.secret) "***" else it
        }}")

        // Use JNI mode on all Android versions to avoid SELinux restrictions
        // Android 10+ blocks execution of standalone binaries from app storage
        tiredvpnProcess = if (true) {
            FileLogger.i(TAG, "Using JNI mode (Android ${Build.VERSION.SDK_INT})")
            NativeProcessJNI(
                args = args.drop(1), // Skip binary path for JNI mode
                onOutput = { line ->
                    FileLogger.d(TAG, "[tiredvpn-jni] $line")
                    parseConnectionInfo(line)
                },
                onError = { line ->
                    FileLogger.e(TAG, "[tiredvpn-jni] $line")
                    parseConnectionInfo(line)
                },
                onExit = { code ->
                    FileLogger.w(TAG, "tiredvpn-jni exited with code $code")
                    val currentState = _state.value
                    FileLogger.d(TAG, "onExit (jni): currentState=$currentState")
                    when (currentState) {
                        is VpnState.Disconnected -> {
                            FileLogger.d(TAG, "onExit (jni): User disconnected, not reconnecting")
                        }
                        is VpnState.Connecting -> {
                            FileLogger.e(TAG, "onExit (jni): Process died during Connecting")
                            val config = ServerRepository.getActiveServer(this@TiredVpnService)
                            if (config != null && config.isValid) {
                                FileLogger.i(TAG, "onExit (jni): Scheduling fast reconnect")
                                scope.launch {
                                    delay(1000)
                                    if (_state.value !is VpnState.Disconnected) {
                                        scheduleAutoReconnect(config)
                                    }
                                }
                            }
                        }
                        is VpnState.Connected -> {
                            scope.launch {
                                FileLogger.e(TAG, "tiredvpn-jni process died while connected, attempting reconnect...")
                                handleControlSocketBroken()
                            }
                        }
                        is VpnState.Error -> {
                            val config = ServerRepository.getActiveServer(this@TiredVpnService)
                            if (config != null && config.isValid) {
                                FileLogger.d(TAG, "onExit (jni): Error state - ensuring reconnect is scheduled")
                                scope.launch {
                                    delay(500)
                                    if (_state.value !is VpnState.Disconnected && _state.value !is VpnState.Connecting) {
                                        scheduleAutoReconnect(config)
                                    }
                                }
                            }
                        }
                    }  // Close when statement
                }  // Close onExit lambda
            )  // Close NativeProcessJNI constructor
        }  // Close if block
        else {  // Start else block
            NativeProcess(
                args = args,
                onOutput = { line ->
                    FileLogger.d(TAG, "[tiredvpn] $line")
                    parseConnectionInfo(line)
                },
                onError = { line ->
                    FileLogger.e(TAG, "[tiredvpn] $line")
                    parseConnectionInfo(line)
                },
                onExit = { code ->
                    FileLogger.w(TAG, "tiredvpn exited with code $code")
                    val currentState = _state.value
                    FileLogger.d(TAG, "onExit: currentState=$currentState")
                    when (currentState) {
                    is VpnState.Disconnected -> {
                        // User disconnected - don't reconnect
                        FileLogger.d(TAG, "onExit: User disconnected, not reconnecting")
                    }
                    is VpnState.Connecting -> {
                        // Process died during connection - likely killed by PhantomProcess killer
                        // We need to trigger reconnect because the catch block may not fire
                        // if the process dies before socket connection is established
                        FileLogger.e(TAG, "onExit: Process died during Connecting - likely PhantomProcess kill")
                        val config = ServerRepository.getActiveServer(this@TiredVpnService)
                        if (config != null && config.isValid) {
                            FileLogger.i(TAG, "onExit: Scheduling fast reconnect after PhantomProcess kill")
                            scope.launch {
                                // Short delay to avoid tight loop if being killed repeatedly
                                delay(1000)
                                // Only reconnect if still not disconnected by user
                                if (_state.value !is VpnState.Disconnected) {
                                    scheduleAutoReconnect(config)
                                }
                            }
                        }
                    }
                    is VpnState.Connected -> {
                        // Process died while connected - try to reconnect
                        // This is different from connection failure - we WERE connected
                        scope.launch {
                            FileLogger.e(TAG, "tiredvpn process died while connected, attempting reconnect...")
                            handleControlSocketBroken()
                        }
                    }
                    is VpnState.Error -> {
                        // Process exited after error - check if reconnect is already scheduled
                        // If not, schedule one (could be PhantomProcess kill during reconnect)
                        val config = ServerRepository.getActiveServer(this@TiredVpnService)
                        if (config != null && config.isValid) {
                            FileLogger.d(TAG, "onExit: Error state - ensuring reconnect is scheduled")
                            scope.launch {
                                delay(500)
                                if (_state.value !is VpnState.Disconnected && _state.value !is VpnState.Connecting) {
                                    scheduleAutoReconnect(config)
                                }
                            }
                        }
                    }
                }  // Close when statement
            }  // Close onExit lambda
            )  // Close NativeProcess constructor
        }.also { it.start() }
    }

    private suspend fun connectToControlSocket(socketPath: String): TunnelConfig? {
        FileLogger.d(TAG, "connectToControlSocket: waiting for socket at $socketPath")

        // Wait for socket to appear
        val socketFile = File(socketPath)
        val deadline = System.currentTimeMillis() + CONTROL_SOCKET_TIMEOUT
        var waitMs = 0L

        while (!socketFile.exists() && System.currentTimeMillis() < deadline) {
            // Check if coroutine was cancelled (e.g., process died or timeout)
            currentCoroutineContext().ensureActive()

            if (tiredvpnProcess?.isRunning == true != true) {
                FileLogger.e(TAG, "connectToControlSocket: tiredvpn process DIED after ${waitMs}ms")
                return null
            }
            delay(100)
            waitMs += 100
            if (waitMs % 1000 == 0L) {
                FileLogger.d(TAG, "connectToControlSocket: still waiting... ${waitMs}ms")
            }
        }

        // Final check after loop
        currentCoroutineContext().ensureActive()

        if (!socketFile.exists()) {
            FileLogger.e(TAG, "connectToControlSocket: socket NOT created within ${CONTROL_SOCKET_TIMEOUT}ms")
            return null
        }
        FileLogger.d(TAG, "connectToControlSocket: socket appeared after ${waitMs}ms")

        // Small delay to ensure socket is listening
        delay(200)

        return try {
            FileLogger.d(TAG, "connectToControlSocket: connecting to LocalSocket...")
            controlSocket = LocalSocket().apply {
                connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                // Set read timeout to prevent indefinite blocking
                // Increased to 60s to allow all strategies to be tried (can take up to 40s)
                setSoTimeout(25_000)  // 25 second timeout (must be < CONNECTION_TIMEOUT=30s)
            }
            FileLogger.d(TAG, "connectToControlSocket: LocalSocket connected!")

            val reader = controlSocket!!.inputStream.bufferedReader()
            val writer = controlSocket!!.outputStream.bufferedWriter()

            // Send connect command
            val connectCmd = JSONObject().apply {
                put("command", "connect")
            }.toString()

            FileLogger.d(TAG, "connectToControlSocket: sending 'connect' command...")
            writer.write(connectCmd)
            writer.newLine()
            writer.flush()
            FileLogger.d(TAG, "connectToControlSocket: 'connect' sent, waiting for response...")

            // Read response with tunnel config
            val response = reader.readLine() ?: throw Exception("No response from control socket")
            FileLogger.d(TAG, "connectToControlSocket: got response: $response")
            val json = JSONObject(response)

            val status = json.optString("status", "")
            FileLogger.d(TAG, "connectToControlSocket: status=$status")

            if (status == "waiting_fd") {
                val config = TunnelConfig(
                    ip = json.getString("ip"),
                    serverIp = json.optString("server_ip", "10.8.0.1"),
                    dns = json.optString("dns", "8.8.8.8"),
                    mtu = json.optInt("mtu", 1400),
                    routes = json.optString("routes", "0.0.0.0/0")
                )
                FileLogger.d(TAG, "connectToControlSocket: SUCCESS - config=$config")
                config
            } else {
                val error = json.optString("error", "Unexpected status: $status")
                FileLogger.e(TAG, "connectToControlSocket: Server rejected: $error")
                null
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "connectToControlSocket: EXCEPTION", e)
            null
        }
    }

    /**
     * Send TUN fd to tiredvpn and get final connection info.
     * Returns the final IP assigned by server, or null on failure.
     *
     * Protocol:
     * 1. Send JSON command with fd attached via SCM_RIGHTS in ONE write
     * 2. Receive response: {"status":"connected",...}
     */
    private suspend fun sendTunFd(fd: Int): String? {
        FileLogger.d(TAG, "sendTunFd: START fd=$fd")
        try {
            val outputStream = controlSocket!!.outputStream
            val reader = controlSocket!!.inputStream.bufferedReader()

            FileLogger.d(TAG, "sendTunFd: got streams, preparing FileDescriptor...")

            // Create FileDescriptor from raw fd
            val fileDescriptor = java.io.FileDescriptor()
            try {
                val field = java.io.FileDescriptor::class.java.getDeclaredField("fd")
                field.isAccessible = true
                field.setInt(fileDescriptor, fd)
            } catch (e: Exception) {
                // Some Android versions use "descriptor" instead of "fd"
                val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(fileDescriptor, fd)
            }

            // Set fd to be sent with the NEXT write - this attaches fd via SCM_RIGHTS
            FileLogger.d(TAG, "sendTunFd: calling setFileDescriptorsForSend...")
            controlSocket!!.setFileDescriptorsForSend(arrayOf(fileDescriptor))
            FileLogger.d(TAG, "sendTunFd: fd prepared for SCM_RIGHTS")

            // Send JSON command - fd will be attached to THIS message via SCM_RIGHTS
            val fdCmd = "{\"command\":\"set_fd\"}\n"
            FileLogger.d(TAG, "sendTunFd: writing set_fd command...")
            outputStream.write(fdCmd.toByteArray())
            outputStream.flush()
            FileLogger.d(TAG, "sendTunFd: set_fd command sent, waiting for response (15s timeout)...")

            // Read confirmation with timeout
            val response = withTimeoutOrNull(15000) {
                withContext(Dispatchers.IO) {
                    FileLogger.d(TAG, "sendTunFd: readLine() blocking...")
                    reader.readLine()
                }
            }

            if (response == null) {
                FileLogger.e(TAG, "sendTunFd: TIMEOUT - no response from tiredvpn after 15s")
                // Check if process is still alive
                val isAlive = tiredvpnProcess?.isRunning == true ?: false
                FileLogger.e(TAG, "sendTunFd: tiredvpn process isRunning=$isAlive")
                return null
            }

            FileLogger.d(TAG, "sendTunFd: got response: $response")
            val json = JSONObject(response)

            val status = json.optString("status", "")
            val connected = json.optBoolean("connected", false)
            val finalIp = json.optString("ip", "")

            // Parse connection metadata from Go binary
            val strategy = json.optString("strategy", "")
            val latencyMs = json.optLong("latency_ms", 0)
            val attempts = json.optInt("attempts", 1)

            FileLogger.d(TAG, "sendTunFd: parsed response - status=$status, connected=$connected, ip=$finalIp, strategy=$strategy, latency=${latencyMs}ms, attempts=$attempts")

            if (status == "connected" && connected) {
                // Store connection metadata for UI
                if (strategy.isNotEmpty()) {
                    connectedStrategy = strategy
                }
                if (latencyMs > 0) {
                    connectedLatencyMs = latencyMs
                }
                if (attempts > 0) {
                    connectedAttempts = attempts
                }
                FileLogger.i(TAG, "sendTunFd: SUCCESS - tunnel is active, final IP: $finalIp, strategy=$connectedStrategy, latency=${connectedLatencyMs}ms")
                return finalIp
            } else {
                // BUG FIX 2: Check if error is handshake EOF - this means server closed connection
                val error = json.optString("error", "")
                FileLogger.e(TAG, "sendTunFd: FAILED - status=$status, error=$error")

                if (error.contains("handshake") && error.contains("EOF")) {
                    FileLogger.w(TAG, "sendTunFd: Handshake EOF detected - server closed connection during TLS handshake")
                }

                return null
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "sendTunFd: EXCEPTION", e)
            return null
        }
    }

    private fun startStatusMonitoring() {
        // Start event listener that handles both periodic status checks and Go events
        statusMonitorJob = scope.launch {
            val reader = controlSocket?.inputStream?.bufferedReader() ?: return@launch

            // Launch a separate coroutine to listen for events from Go
            val eventListener = launch {
                // Don't exit loop just because state changed - we need to catch connection_dead
                while (isActive) {
                    val line: String?
                    try {
                        line = withContext(Dispatchers.IO) { reader.readLine() }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Socket timeout is expected due to setSoTimeout(15_000)
                        // Just continue the loop - the connection might still be fine
                        FileLogger.d(TAG, "Event listener: socket read timeout, continuing...")
                        continue
                    } catch (e: Exception) {
                        if (isActive) {
                            FileLogger.e(TAG, "Event listener error", e)
                            handleControlSocketBroken()
                        }
                        break
                    }

                    if (line == null) {
                        FileLogger.w(TAG, "Event listener: socket closed")
                        handleControlSocketBroken()
                        break
                    }

                    // Parse response/event
                    try {
                        val json = JSONObject(line)

                            // Check if this is an EventMessage (has "event" field)
                            val eventType = json.optString("event", "")
                            if (eventType.isNotEmpty()) {
                                val timestamp = json.optLong("timestamp", 0)
                                val data = json.optString("data", "")
                                FileLogger.i(TAG, "=== EVENT: $eventType (data=$data, ts=$timestamp) ===")

                                when (eventType) {
                                    "keepalive" -> {
                                        // Update keepalive time for health check
                                        lastKeepaliveTime = System.currentTimeMillis()
                                        FileLogger.d(TAG, "Keepalive received from server")
                                    }
                                    "connection_dead" -> {
                                        FileLogger.e(TAG, "Connection dead: $data")
                                        // Trigger reconnect - the Go side already stopped relay
                                        handleControlSocketBroken()
                                    }
                                    "reconnecting" -> {
                                        FileLogger.i(TAG, "Go is reconnecting: $data")
                                        _state.value = VpnState.Connecting
                                        updateNotification("Reconnecting...")
                                    }
                                    "connected" -> {
                                        FileLogger.i(TAG, "Reconnect successful")
                                        lastKeepaliveTime = System.currentTimeMillis()
                                        // Parse reconnect metadata from event data (JSON)
                                        if (data.startsWith("{")) {
                                            try {
                                                val meta = org.json.JSONObject(data)
                                                val strategy = meta.optString("strategy", "")
                                                val latencyMs = meta.optLong("latency_ms", 0)
                                                val attempts = meta.optInt("attempts", 0)
                                                if (strategy.isNotEmpty()) connectedStrategy = strategy
                                                if (latencyMs > 0) connectedLatencyMs = latencyMs
                                                if (attempts > 0) connectedAttempts = attempts
                                            } catch (e: Exception) {
                                                FileLogger.w(TAG, "Failed to parse reconnect metadata: $e")
                                            }
                                        }
                                        _state.value = VpnState.Connected(
                                            strategy = connectedStrategy,
                                            latencyMs = connectedLatencyMs,
                                            attempts = connectedAttempts
                                        )
                                        updateNotification("Connected • $currentVpnIp")
                                    }
                                    else -> {
                                        FileLogger.w(TAG, "Unknown event: $eventType")
                                    }
                                }
                                continue
                            }

                            // Otherwise it's a ControlResponse (has "status" field)
                            val status = json.optString("status", "")
                            when (status) {
                                "ok" -> {
                                    // Status response - rely on keepalive events for health check
                                    // Don't check 'connected' field here - it causes false positives
                                    // during network_available and other command responses
                                    FileLogger.d(TAG, "Command acknowledged: $line")
                                }
                                "connected" -> {
                                    // Response to network_changed or reconnect
                                    FileLogger.d(TAG, "Reconnect confirmed: $line")
                                    lastKeepaliveTime = System.currentTimeMillis()
                                    // Update latency/strategy from reconnect response
                                    val strategy = json.optString("strategy", "")
                                    val latencyMs = json.optLong("latency_ms", 0)
                                    val attempts = json.optInt("attempts", 0)
                                    if (strategy.isNotEmpty()) connectedStrategy = strategy
                                    if (latencyMs > 0) connectedLatencyMs = latencyMs
                                    if (attempts > 0) connectedAttempts = attempts
                                    _state.value = VpnState.Connected(
                                        strategy = connectedStrategy,
                                        latencyMs = connectedLatencyMs,
                                        attempts = connectedAttempts
                                    )
                                }
                                "error" -> {
                                    val error = json.optString("error", "unknown")
                                    FileLogger.e(TAG, "Error from Go: $error")
                                    // Trigger reconnect on certain errors
                                    if (error.contains("reconnect", ignoreCase = true) ||
                                        error.contains("failed", ignoreCase = true) ||
                                        error.contains("timeout", ignoreCase = true) ||
                                        error.contains("connection", ignoreCase = true)) {
                                        FileLogger.w(TAG, "Error requires reconnect, triggering handleControlSocketBroken()")
                                        handleControlSocketBroken()
                                    }
                                }
                                else -> {
                                    FileLogger.d(TAG, "Received: $line")
                                }
                            }
                        } catch (e: Exception) {
                            FileLogger.w(TAG, "Failed to parse response: $line", e)
                        }
                    }
                }

            // Periodic status checks (health check now relies on events from Go)
            val writer = controlSocket?.outputStream?.bufferedWriter()

            // Keep sending status checks while event listener is active
            // Don't check VpnState here - we need to keep listening even during reconnect
            while (isActive && eventListener.isActive) {
                delay(30_000) // Check every 30 seconds (Go handles keepalive tracking)

                // Only send status check if connected
                val currentState = _state.value
                if (currentState !is VpnState.Connected && currentState !is VpnState.Connecting) {
                    FileLogger.d(TAG, "Status monitor: state is $currentState, stopping")
                    break
                }

                // Send status check to control socket
                try {
                    if (writer != null && currentState is VpnState.Connected) {
                        val statusCmd = JSONObject().apply {
                            put("command", "status")
                        }.toString()

                        writer.write(statusCmd)
                        writer.newLine()
                        writer.flush()
                        FileLogger.d(TAG, "Sent status check")
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Status check send error", e)
                    // Don't break - let event listener handle socket errors
                }
            }

            // Don't cancel event listener here - it will stop on its own when socket closes
            // or when handleControlSocketBroken() cancels the whole statusMonitorJob
            FileLogger.d(TAG, "Status monitor loop ended, waiting for event listener...")
            eventListener.join()
        }
    }

    /**
     * Internal process watchdog - checks if Go process is alive every 30 seconds.
     * This is faster than WorkManager's 15 minute minimum and helps detect
     * PhantomProcess kills quickly.
     */
    private fun startProcessWatchdog() {
        processWatchdogJob?.cancel()

        processWatchdogJob = scope.launch {
            FileLogger.i(TAG, "=== PROCESS WATCHDOG STARTED ===")

            while (isActive) {
                delay(processWatchdogIntervalMs)

                val currentState = _state.value

                // Only check if we should be connected
                if (currentState is VpnState.Disconnected) {
                    FileLogger.d(TAG, "Process watchdog: Disconnected state, stopping")
                    break
                }

                // Skip if already reconnecting
                if (currentState is VpnState.Connecting) {
                    FileLogger.d(TAG, "Process watchdog: Already connecting, skip check")
                    continue
                }

                // Check if Go process is alive
                val isProcessAlive = tiredvpnProcess?.isRunning == true == true

                if (!isProcessAlive && currentState is VpnState.Connected) {
                    FileLogger.e(TAG, "=== PROCESS WATCHDOG: Go process is DEAD but state is Connected! ===")
                    FileLogger.i(TAG, "Process watchdog triggering reconnect...")
                    handleControlSocketBroken()
                } else if (!isProcessAlive && currentState is VpnState.Error) {
                    // Process died and we're in error state - ensure reconnect happens
                    val config = ServerRepository.getActiveServer(this@TiredVpnService)
                    if (config != null && config.isValid) {
                        FileLogger.i(TAG, "Process watchdog: Process dead in Error state, triggering reconnect")
                        scheduleAutoReconnect(config)
                    }
                } else {
                    FileLogger.d(TAG, "Process watchdog: OK (processAlive=$isProcessAlive, state=$currentState)")
                }
            }

            FileLogger.i(TAG, "=== PROCESS WATCHDOG STOPPED ===")
        }
    }

    private fun stopProcessWatchdog() {
        processWatchdogJob?.cancel()
        processWatchdogJob = null
    }

    private fun sendDisconnectCommand() {
        try {
            val writer = controlSocket?.outputStream?.bufferedWriter() ?: return

            val disconnectCmd = JSONObject().apply {
                put("command", "disconnect")
            }.toString()

            writer.write(disconnectCmd)
            writer.newLine()
            writer.flush()

            FileLogger.d(TAG, "Sent disconnect command to tiredvpn")
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to send disconnect command", e)
        }
    }

    private fun startNetworkMonitoring() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        lastNetworkAvailableTime = System.currentTimeMillis()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            private var currentNetwork: Network? = null
            private var hadNetworkLoss = false

            override fun onAvailable(network: Network) {
                FileLogger.i(TAG, "=== NETWORK AVAILABLE: $network (current: $currentNetwork, hadLoss: $hadNetworkLoss) ===")
                lastNetworkAvailableTime = System.currentTimeMillis()

                // ITERATION 3: Stop network watchdog - we have network now
                stopNetworkWatchdog()

                // Stop network recovery job - we have network now
                stopNetworkRecoveryJob()

                // BUG FIX 1: Check if network ACTUALLY changed (by IP address, not just Network ID)
                // Android sometimes changes Network ID even though it's the same WiFi/cellular
                var networkActuallyChanged = false
                try {
                    val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val linkProps = connectivityManager.getLinkProperties(network)
                    linkProps?.let { props ->
                        val addresses = props.linkAddresses
                            .filter { it.address is java.net.Inet4Address }
                            .map { it.address.hostAddress }
                            .sorted()
                            .joinToString(",")

                        // Check if IP addresses actually changed
                        if (lastLinkProperties.isNotEmpty() && addresses != lastLinkProperties) {
                            FileLogger.i(TAG, "Network ACTUALLY changed: IP from [$lastLinkProperties] to [$addresses]")
                            networkActuallyChanged = true
                        } else if (lastLinkProperties.isEmpty()) {
                            // First time seeing this network
                            FileLogger.d(TAG, "First network initialization: [$addresses]")
                        } else {
                            // Same network, just different Network ID
                            FileLogger.d(TAG, "Network ID changed (${currentNetwork} -> $network) but IP same: [$addresses]")
                        }

                        lastLinkProperties = addresses
                    }
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Failed to get link properties", e)
                }

                // Trigger reconnect if:
                // 1. We had a network loss and now network is back (only if not already handled by networkRecoveryJob)
                // 2. Network ACTUALLY switched (IP address changed, not just Network ID)
                // Use isNetworkLost as guard: networkRecoveryJob sets it to false before triggering reconnect,
                // so if it's already false here, the recovery was already handled — skip duplicate trigger.
                val needsReconnect = (hadNetworkLoss && isNetworkLost) || networkActuallyChanged

                // Grace period: after fresh connection NetworkCallback fires onAvailable
                // for all existing networks immediately — suppress spurious reconnect signals
                if (needsReconnect && !hadNetworkLoss && System.currentTimeMillis() - connectionTime < 5_000L) {
                    FileLogger.d(TAG, "onAvailable: Within 5s grace period post-connect, suppressing spurious reconnect")
                    currentNetwork = network
                    return
                }

                // ITERATION 3: CRITICAL - Send explicit network_available signal to Go
                // This is the main fix for "waiting for network..." stuck issue
                // ITERATION 4 FIX: Only send signal if we actually had network loss!
                if (needsReconnect) {
                    sendNetworkAvailableSignal()
                }

                if (needsReconnect) {
                    FileLogger.i(TAG, "Network recovered/switched, triggering FAST reconnect")
                    hadNetworkLoss = false
                    isNetworkLost = false

                    // CRITICAL: Check if Go process is still alive before triggering reconnect
                    // If process died during network loss, we need full reconnect with cleanup
                    val isProcessAlive = tiredvpnProcess?.isRunning == true == true
                    FileLogger.d(TAG, "onAvailable: Go process alive = $isProcessAlive")

                    if (!isProcessAlive && _state.value is VpnState.Connected) {
                        FileLogger.w(TAG, "onAvailable: Go process is DEAD, forcing full reconnect with cleanup")
                        handleControlSocketBroken()
                    } else {
                        // Process is alive - try graceful reconnect
                        triggerReconnectAfterNetworkRecovery()
                    }
                }
                currentNetwork = network
            }

            override fun onLost(network: Network) {
                FileLogger.i(TAG, "=== NETWORK LOST: $network - starting watchdog and recovery job ===")
                // Mark that we lost network - this allows fast reconnect without TCP checks
                hadNetworkLoss = true
                isNetworkLost = true
                currentNetwork = null

                // ITERATION 3: Start watchdog timer for long disconnects
                // If offline >45s, force full VPN restart
                startNetworkWatchdog()

                // Start periodic network recovery check
                // This is crucial for reconnecting after long network outages (1+ minute)
                startNetworkRecoveryJob()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // ITERATION 3: Send network_available signal if capabilities improved
                // This helps when WiFi reconnects but onAvailable doesn't fire
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                FileLogger.d(TAG, "Network capabilities changed: $network (internet=$hasInternet, validated=$hasValidated)")

                if (hasInternet && hasValidated && hadNetworkLoss) {
                    FileLogger.i(TAG, "Capabilities improved after loss - sending network_available signal")
                    sendNetworkAvailableSignal()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                // BUG FIX 1: Only trigger reconnect if IP addresses actually changed
                // On some devices (Pixel 6) this fires constantly even when nothing changed
                val addresses = linkProperties.linkAddresses
                    .filter { it.address is java.net.Inet4Address } // Only IPv4
                    .map { it.address.hostAddress }
                    .sorted()
                    .joinToString(",")

                FileLogger.i(TAG, "Network link properties changed: $network, addresses: [$addresses]")

                // Only trigger reconnect if this is our current network AND addresses actually changed
                if (network == currentNetwork) {
                    if (addresses != lastLinkProperties) {
                        FileLogger.i(TAG, "IP addresses changed from [$lastLinkProperties] to [$addresses]")
                        lastLinkProperties = addresses
                        sendNetworkChangedCommand()
                    } else {
                        FileLogger.d(TAG, "Link properties changed but IP addresses are same, ignoring")
                    }
                }
            }
        }

        // Use default network callback to properly track network loss
        // This tracks the system's preferred network (WiFi when available, then cellular)
        // When VPN is active, the default network changes - but we want underlying network
        try {
            // First, try to track non-VPN networks with INTERNET capability
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()

            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to register network callback with NOT_VPN, using default", e)
            // Fallback to default callback
            connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
        }
        FileLogger.d(TAG, "Network monitoring started")
    }

    private fun stopNetworkMonitoring() {
        stopNetworkRecoveryJob()
        stopNetworkWatchdog()
        networkCallback?.let { callback ->
            try {
                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(callback)
                FileLogger.d(TAG, "Network monitoring stopped")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    /**
     * ITERATION 3: Send explicit network_available command to Go process.
     * This is CRITICAL because Go cannot detect network restoration on its own.
     * Without this, Go waits forever in "waiting for network..." state.
     */
    private fun sendNetworkAvailableSignal() {
        if (_state.value is VpnState.Disconnected) {
            FileLogger.d(TAG, "sendNetworkAvailableSignal: VPN disconnected, skipping")
            return
        }

        // Debounce: ignore signals within 2 seconds of each other to prevent cascade
        val now = System.currentTimeMillis()
        if (now - lastNetworkSignalSentTime < 2000) {
            FileLogger.d(TAG, "sendNetworkAvailableSignal: debounced (${now - lastNetworkSignalSentTime}ms since last)")
            return
        }
        lastNetworkSignalSentTime = now

        scope.launch {
            try {
                val writer = controlSocket?.outputStream?.bufferedWriter()
                if (writer != null) {
                    val cmd = JSONObject().apply {
                        put("command", "network_available")
                        put("timestamp", System.currentTimeMillis())
                    }.toString()

                    writer.write(cmd)
                    writer.newLine()
                    writer.flush()

                    FileLogger.i(TAG, "=== SENT network_available SIGNAL TO GO ===")
                } else {
                    FileLogger.d(TAG, "sendNetworkAvailableSignal: control socket not available")
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to send network_available signal: ${e.message}")
            }
        }
    }

    /**
     * ITERATION 3: Network watchdog timer - force restart if offline >45s.
     * This is CRITICAL for fixing long disconnect (60s+) scenario.
     */
    private fun startNetworkWatchdog() {
        // Cancel any existing watchdog
        stopNetworkWatchdog()

        val disconnectTime = System.currentTimeMillis()

        networkWatchdogJob = scope.launch {
            FileLogger.i(TAG, "=== NETWORK WATCHDOG STARTED (timeout=${networkWatchdogTimeoutMs}ms) ===")

            delay(networkWatchdogTimeoutMs)

            // Check if still offline
            if (isNetworkLost) {
                val offlineTime = System.currentTimeMillis() - disconnectTime
                FileLogger.e(TAG, "=== NETWORK WATCHDOG TRIGGERED: ${offlineTime}ms offline, forcing VPN restart ===")

                // Force full VPN restart
                handleControlSocketBroken()
            } else {
                FileLogger.i(TAG, "Network watchdog: Network recovered before timeout")
            }
        }
    }

    private fun stopNetworkWatchdog() {
        networkWatchdogJob?.cancel()
        networkWatchdogJob = null
    }

    /**
     * ITERATION 3: Health check - ping Go process every 10s.
     * Detects dead/zombie processes and triggers restart.
     */
    private fun startHealthCheck() {
        // Cancel any existing health check
        stopHealthCheck()

        healthCheckJob = scope.launch {
            FileLogger.i(TAG, "=== HEALTH CHECK STARTED (interval=${healthCheckIntervalMs}ms) ===")

            while (isActive) {
                delay(healthCheckIntervalMs)

                val currentState = _state.value

                // Only check if we should be connected
                if (currentState is VpnState.Disconnected) {
                    FileLogger.d(TAG, "Health check: Disconnected state, stopping")
                    break
                }

                // Skip if already reconnecting
                if (currentState is VpnState.Connecting || isReconnecting) {
                    FileLogger.d(TAG, "Health check: Already connecting/reconnecting, skip")
                    continue
                }

                // Check if Go process is alive
                val isProcessAlive = tiredvpnProcess?.isRunning == true == true

                if (!isProcessAlive && currentState is VpnState.Connected) {
                    FileLogger.e(TAG, "=== HEALTH CHECK FAILED: Go process is DEAD! ===")
                    handleControlSocketBroken()
                    break
                } else {
                    FileLogger.d(TAG, "Health check: OK (alive=$isProcessAlive, state=$currentState)")
                }
            }

            FileLogger.i(TAG, "=== HEALTH CHECK STOPPED ===")
        }
    }

    private fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    /**
     * PIXEL FIX: Active network monitor - polls activeNetwork every 2 seconds.
     * This is a CRITICAL fix for Pixel devices where NetworkCallback.onAvailable()
     * is unreliable during WiFi <-> Mobile transitions.
     *
     * Why this is needed:
     * - On Pixel devices, NetworkCallback sometimes doesn't fire on network recovery
     * - Airplane mode on/off can miss onAvailable() callback entirely
     * - Fast network switching (WiFi->Mobile->WiFi) can drop events
     *
     * This aggressive polling ensures we NEVER miss network changes.
     */
    private fun startActiveNetworkMonitor() {
        // Cancel any existing monitor
        stopActiveNetworkMonitor()

        activeNetworkMonitorJob = scope.launch {
            FileLogger.i(TAG, "=== ACTIVE NETWORK MONITOR STARTED (Pixel fix, interval=${activeNetworkMonitorIntervalMs}ms) ===")

            var lastActiveNetwork: Network? = null
            var lastNetworkType: String = "unknown"

            while (isActive) {
                try {
                    val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val currentNetwork = cm.activeNetwork
                    val capabilities = currentNetwork?.let { cm.getNetworkCapabilities(it) }

                    // Determine network type
                    val currentNetworkType = when {
                        capabilities == null -> "none"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                        else -> "other"
                    }

                    // Detect network changes
                    val networkChanged = currentNetwork != lastActiveNetwork
                    val typeChanged = currentNetworkType != lastNetworkType

                    if (networkChanged || typeChanged) {
                        FileLogger.i(TAG, "=== ACTIVE MONITOR: Network changed! ===")
                        FileLogger.i(TAG, "  Old: $lastActiveNetwork ($lastNetworkType)")
                        FileLogger.i(TAG, "  New: $currentNetwork ($currentNetworkType)")

                        // Network appeared (was null, now not null)
                        if (lastActiveNetwork == null && currentNetwork != null) {
                            FileLogger.i(TAG, "ACTIVE MONITOR: Network APPEARED - sending network_available signal")
                            sendNetworkAvailableSignal()

                            // If we were in network loss state, trigger reconnect
                            if (isNetworkLost) {
                                FileLogger.i(TAG, "ACTIVE MONITOR: Triggering recovery from network loss")
                                isNetworkLost = false
                                triggerReconnectAfterNetworkRecovery()
                            }
                        }
                        // Network changed (different network object or type)
                        else if (currentNetwork != null && (networkChanged || typeChanged)) {
                            FileLogger.i(TAG, "ACTIVE MONITOR: Network SWITCHED - sending network_changed")
                            // Give NetworkCallback 500ms to handle it first
                            delay(500)
                            // Only trigger if still connected (NetworkCallback might have handled it)
                            if (_state.value is VpnState.Connected) {
                                sendNetworkChangedCommand(forceReconnect = false, isCritical = true)
                            }
                        }
                        // Network disappeared (was not null, now null)
                        else if (currentNetwork == null) {
                            FileLogger.w(TAG, "ACTIVE MONITOR: Network DISAPPEARED")
                            isNetworkLost = true
                        }

                        lastActiveNetwork = currentNetwork
                        lastNetworkType = currentNetworkType
                    }

                } catch (e: Exception) {
                    FileLogger.w(TAG, "Active network monitor error: ${e.message}")
                }

                delay(activeNetworkMonitorIntervalMs)
            }

            FileLogger.i(TAG, "=== ACTIVE NETWORK MONITOR STOPPED ===")
        }
    }

    private fun stopActiveNetworkMonitor() {
        activeNetworkMonitorJob?.cancel()
        activeNetworkMonitorJob = null
    }

    /**
     * Start a background job that periodically checks for network availability.
     * This is crucial for reconnecting after long network outages (1+ minute)
     * when NetworkCallback.onAvailable() might not fire reliably.
     */
    private fun startNetworkRecoveryJob() {
        // Cancel any existing job
        stopNetworkRecoveryJob()

        networkRecoveryJob = scope.launch {
            FileLogger.i(TAG, "=== NETWORK RECOVERY JOB STARTED ===")
            var checkCount = 0

            while (isActive && isNetworkLost) {
                // IMPROVED: More aggressive checking for faster recovery
                // Check every 2 seconds for the first 30 seconds
                // Then every 5 seconds for the next minute
                // Then every 10 seconds afterwards
                val checkInterval = when {
                    checkCount < 15 -> 2_000L  // First 30 seconds: 2s interval
                    checkCount < 27 -> 5_000L  // Next 60 seconds: 5s interval
                    else -> 10_000L            // After 90 seconds: 10s interval
                }
                delay(checkInterval)
                checkCount++

                if (!isNetworkLost) {
                    FileLogger.i(TAG, "Network recovery job: isNetworkLost=false, exiting")
                    break
                }

                // Skip check if we're already reconnecting
                val currentState = _state.value
                if (currentState is VpnState.Connecting) {
                    FileLogger.d(TAG, "Network recovery job: already connecting, waiting...")
                    continue
                }

                FileLogger.d(TAG, "Network recovery job: checking network (attempt $checkCount)")

                // Check if we have network connectivity using ConnectivityManager
                val hasNetwork = checkNetworkAvailability()
                if (hasNetwork) {
                    FileLogger.i(TAG, "=== NETWORK RECOVERY JOB: Network restored! Triggering reconnect ===")
                    isNetworkLost = false
                    lastNetworkAvailableTime = System.currentTimeMillis()

                    // ITERATION 3: Send explicit network_available signal
                    sendNetworkAvailableSignal()

                    triggerReconnectAfterNetworkRecovery()
                    break
                } else {
                    // Update notification to show we're waiting for network
                    if (currentState is VpnState.Connected || currentState is VpnState.Error) {
                        val waitTime = (System.currentTimeMillis() - lastNetworkAvailableTime) / 1000
                        updateNotification("Waiting for network... (${waitTime}s)")
                        FileLogger.d(TAG, "Network recovery job: Still no network after ${waitTime}s")
                    }
                }
            }
            FileLogger.i(TAG, "=== NETWORK RECOVERY JOB ENDED ===")
        }
    }

    private fun stopNetworkRecoveryJob() {
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
    }

    /**
     * Check if network is available using ConnectivityManager.
     * This is a backup check when NetworkCallback might not fire.
     */
    private fun checkNetworkAvailability(): Boolean {
        return try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

            // Check for internet capability (not just network connection)
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            FileLogger.d(TAG, "Network check: hasInternet=$hasInternet, hasValidated=$hasValidated")
            hasInternet && hasValidated
        } catch (e: Exception) {
            FileLogger.w(TAG, "Network check failed: ${e.message}")
            false
        }
    }

    /**
     * Trigger reconnection after network is recovered.
     * This handles both the case when connection is still "alive" (but broken)
     * and when it's already in error state.
     */
    private fun triggerReconnectAfterNetworkRecovery() {
        // Guard: skip if reconnect already in progress
        if (isReconnecting) {
            FileLogger.d(TAG, "triggerReconnectAfterNetworkRecovery: reconnect in progress, skipping")
            return
        }

        val currentState = _state.value
        FileLogger.i(TAG, "triggerReconnectAfterNetworkRecovery: currentState=$currentState")

        when (currentState) {
            is VpnState.Connected -> {
                // CRITICAL: Before trying network_changed, verify VPN interface is still valid
                // Network change might have invalidated the old tun0 interface
                val vpnFd = vpnInterface
                if (vpnFd == null || !isVpnInterfaceValid(vpnFd)) {
                    FileLogger.w(TAG, "VPN interface is invalid after network recovery, forcing full reconnect")
                    handleControlSocketBroken()
                    return
                }

                // Connection is "alive" and interface is valid - try to refresh it
                FileLogger.i(TAG, "Connected state - sending network_changed command")
                sendNetworkChangedCommand(forceReconnect = true)
            }
            is VpnState.Error -> {
                // Already in error state - trigger full reconnect
                FileLogger.i(TAG, "Error state - triggering full reconnect")
                handleControlSocketBroken()
            }
            is VpnState.Connecting -> {
                // Already reconnecting - do nothing
                FileLogger.d(TAG, "Already connecting - no action needed")
            }
            is VpnState.Disconnected -> {
                // User disconnected - don't auto-reconnect
                FileLogger.d(TAG, "Disconnected state - not auto-reconnecting")
            }
        }
    }

    /**
     * Check if VPN interface file descriptor is still valid.
     * An invalid FD means the interface was destroyed (e.g., after network change).
     *
     * PIXEL FIX: Enhanced validation that checks OS-level FD validity, not just Java wrapper.
     */
    private fun isVpnInterfaceValid(vpnFd: ParcelFileDescriptor): Boolean {
        return try {
            // Try to get fd value - will throw if closed/invalid
            val fd = vpnFd.fd
            if (fd < 0) {
                FileLogger.w(TAG, "VPN interface FD is negative: $fd")
                return false
            }

            // Check if FileDescriptor is valid
            if (!vpnFd.fileDescriptor.valid()) {
                FileLogger.w(TAG, "VPN interface FileDescriptor is invalid")
                return false
            }

            // CRITICAL: Check if FD is still valid at OS level (API 30+)
            // This catches cases where FD is valid in Java but closed/invalid in kernel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    android.system.Os.fcntlInt(vpnFd.fileDescriptor, android.system.OsConstants.F_GETFL, 0)
                } catch (e: android.system.ErrnoException) {
                    FileLogger.w(TAG, "VPN interface FD validation failed at OS level: ${e.message}")
                    return false
                }
            }

            true
        } catch (e: Exception) {
            FileLogger.w(TAG, "VPN interface FD validation failed: ${e.message}")
            false
        }
    }

    private fun sendNetworkChangedCommand(forceReconnect: Boolean = false, isCritical: Boolean = false) {
        // Guard: skip if reconnect already in progress to prevent race conditions in Go
        if (isReconnecting) {
            FileLogger.d(TAG, "sendNetworkChangedCommand: reconnect in progress, skipping")
            return
        }

        if (_state.value !is VpnState.Connected) {
            FileLogger.d(TAG, "sendNetworkChangedCommand: state is not Connected (${_state.value}), skipping")
            return
        }

        val now = System.currentTimeMillis()

        // PIXEL FIX: Skip debounce for critical events (from active network monitor)
        if (!forceReconnect && !isCritical) {
            // Ignore network events in first 3 seconds after connection
            if (now - connectionTime < 3000) {
                FileLogger.d(TAG, "Ignoring network change - too soon after connection")
                return
            }

            // Debounce - ignore events within 3 seconds of each other
            if (now - lastNetworkChangeTime < 3000) {
                FileLogger.d(TAG, "Ignoring network change - debounce")
                return
            }
        } else {
            if (forceReconnect) {
                FileLogger.i(TAG, "Force reconnect requested, bypassing debounce")
            }
            if (isCritical) {
                FileLogger.i(TAG, "Critical network change (from active monitor), bypassing debounce")
            }
        }
        lastNetworkChangeTime = now

        scope.launch {
            try {
                FileLogger.i(TAG, "=== NETWORK CHANGED - Recreating TUN interface ===")

                // Check if Go process is still alive - if not, do full reconnect
                if (tiredvpnProcess?.isRunning == true != true) {
                    FileLogger.w(TAG, "Go process is dead, triggering full reconnect")
                    handleControlSocketBroken()
                    return@launch
                }

                // Check if control socket is still valid
                if (controlSocket == null) {
                    FileLogger.w(TAG, "Control socket is null, triggering full reconnect")
                    handleControlSocketBroken()
                    return@launch
                }

                // Get current IP - use saved IP or fallback
                val currentState = _state.value
                if (currentState !is VpnState.Connected) {
                    FileLogger.w(TAG, "State changed to $currentState during network change, triggering full reconnect")
                    handleControlSocketBroken()
                    return@launch
                }
                val currentIp = if (currentVpnIp.isNotEmpty()) currentVpnIp else "10.9.0.2"
                FileLogger.i(TAG, "Using current VPN IP: $currentIp")

                // Create new VPN interface (old one may be invalid after network change)
                val config = ServerRepository.getActiveServer(this@TiredVpnService) ?: return@launch
                val tunConfig = TunnelConfig(
                    ip = currentIp,
                    serverIp = "10.9.0.1",
                    dns = "8.8.8.8",
                    mtu = 1400,
                    routes = "0.0.0.0/0"
                )

                // CRITICAL FIX: Create new interface FIRST, then swap, then close old
                // This prevents packet loss and Go process crashes during network change
                FileLogger.i(TAG, "Creating NEW VPN interface BEFORE closing old one (atomic swap)")

                // Try to create new VPN interface with retry
                var newVpnFd: ParcelFileDescriptor? = null
                var retries = 0
                val maxRetries = 3

                while (newVpnFd == null && retries < maxRetries) {
                    if (retries > 0) {
                        FileLogger.d(TAG, "Retrying VPN interface creation (attempt ${retries + 1}/$maxRetries)")
                        delay(500) // Wait 500ms before retry
                    }

                    newVpnFd = establishVpn(config, tunConfig)
                    retries++

                    if (newVpnFd == null && retries < maxRetries) {
                        FileLogger.w(TAG, "Failed to create VPN interface, will retry...")
                    }
                }

                if (newVpnFd == null) {
                    FileLogger.e(TAG, "Failed to create new VPN interface after $maxRetries attempts")
                    handleControlSocketBroken()
                    return@launch
                }

                FileLogger.i(TAG, "New VPN interface created successfully, fd=${newVpnFd.fd}")

                // Atomically swap interfaces
                val oldVpnFd = vpnInterface
                vpnInterface = newVpnFd
                FileLogger.i(TAG, "VPN interface swapped atomically")

                // Send network_changed command with new fd
                sendNetworkChangedWithFd(newVpnFd.fd)

                // NOW close old interface AFTER new one is active
                oldVpnFd?.let { oldVpn ->
                    FileLogger.d(TAG, "Closing old VPN interface fd=${oldVpn.fd}")
                    try { oldVpn.close() } catch (e: Exception) {
                        FileLogger.w(TAG, "Error closing old VPN interface: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to handle network change: ${e.message}", e)
                // Any error during network change recovery - trigger full reconnect
                FileLogger.e(TAG, "Network change failed, triggering full reconnect...")
                handleControlSocketBroken()
            }
        }
    }

    private suspend fun sendNetworkChangedWithFd(fd: Int) {
        try {
            val outputStream = controlSocket!!.outputStream

            // Create FileDescriptor from raw fd
            val fileDescriptor = java.io.FileDescriptor()
            try {
                val field = java.io.FileDescriptor::class.java.getDeclaredField("fd")
                field.isAccessible = true
                field.setInt(fileDescriptor, fd)
            } catch (e: Exception) {
                val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(fileDescriptor, fd)
            }

            // Set fd to be sent with the NEXT write via SCM_RIGHTS
            controlSocket!!.setFileDescriptorsForSend(arrayOf(fileDescriptor))

            // Send network_changed command with fd attached
            val cmd = "{\"command\":\"network_changed\"}\n"
            outputStream.write(cmd.toByteArray())
            outputStream.flush()

            FileLogger.i(TAG, "Sent network_changed command with new TUN fd=$fd")
            // Response will be handled by eventListener in startStatusMonitoring

        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to send network_changed with fd", e)
            throw e
        }
    }

    /**
     * ITERATION 2: Improved handleControlSocketBroken with mutex to prevent parallel reconnects.
     * Now uses reconnectMutex to ensure only ONE reconnect happens at a time.
     *
     * CRITICAL FIX: Mutex is now released INSIDE the coroutine (after executeReconnectSequence()
     * finishes), not immediately after launching the coroutine. The old code released the mutex
     * in the outer finally{} block, which ran BEFORE the async coroutine completed — allowing
     * a second handleControlSocketBroken() call to slip through and cause concurrent reconnects
     * that crashed the Go runtime (SIGABRT).
     */
    private fun handleControlSocketBroken() {
        FileLogger.d(TAG, "handleControlSocketBroken: Called (state=${_state.value})")

        val state = _state.value
        if (state is VpnState.Disconnected) {
            FileLogger.d(TAG, "handleControlSocketBroken: State is Disconnected - not reconnecting (user action)")
            return
        }

        // Fast path: skip if already reconnecting
        if (isReconnecting) {
            FileLogger.w(TAG, "handleControlSocketBroken: Already reconnecting (isReconnecting=true), skipping")
            return
        }

        // CRITICAL: Try to acquire mutex lock - skip if already locked
        if (!reconnectMutex.tryLock()) {
            FileLogger.w(TAG, "handleControlSocketBroken: Reconnect already in progress (mutex locked), skipping")
            return
        }

        // NOTE: mutex is now released INSIDE the coroutine, not here
        FileLogger.d(TAG, "handleControlSocketBroken: Mutex acquired, proceeding")

        val now = System.currentTimeMillis()

        // Reset reconnect counter if connection was stable for a while
        if (now - connectionTime > reconnectCooldownMs) {
            FileLogger.d(TAG, "handleControlSocketBroken: Resetting reconnect attempts (cooldown expired)")
            reconnectAttempts = 0
        }

        reconnectAttempts++
        lastReconnectTime = now

        FileLogger.e(TAG, "handleControlSocketBroken: Control socket broken - reconnecting (attempt $reconnectAttempts)")

        _state.value = VpnState.Error("Reconnecting...")

        // Cancel any pending reconnect job
        pendingReconnectJob?.cancel()

        // Launch reconnect sequence — mutex is released when coroutine completes
        ensureScopeActive()
        pendingReconnectJob = scope.launch {
            try {
                withContext(NonCancellable) {
                    isReconnecting = true
                    try {
                        executeReconnectSequence()
                        FileLogger.d(TAG, "handleControlSocketBroken: executeReconnectSequence() completed")
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "handleControlSocketBroken: executeReconnectSequence() failed", e)
                        throw e
                    } finally {
                        isReconnecting = false
                        FileLogger.d(TAG, "handleControlSocketBroken: Cleared isReconnecting flag")
                    }
                }
            } catch (e: CancellationException) {
                FileLogger.w(TAG, "handleControlSocketBroken: Reconnect coroutine cancelled", e)
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "handleControlSocketBroken: Reconnect coroutine failed", e)
            } finally {
                reconnectMutex.unlock()
                FileLogger.d(TAG, "handleControlSocketBroken: Mutex released (coroutine done)")
            }
        }
    }

    /**
     * ITERATION 2: Extracted reconnect logic into separate function.
     * This is always called with reconnectMutex held.
     *
     * ITERATION 2.1 (P0 FIX): Uses NonCancellable context for critical cleanup sections
     * to prevent JobCancellationException during resource cleanup.
     */
    private suspend fun executeReconnectSequence() {
        try {
            FileLogger.d(TAG, "executeReconnectSequence: Reconnect sequence STARTED")

            // CRITICAL SECTION: Cleanup operations must not be cancelled
            withContext(NonCancellable) {
                FileLogger.d(TAG, "executeReconnectSequence: Entered NonCancellable context for cleanup")

                // Step 0: Cancel active connectionJob to prevent race with resource cleanup
                FileLogger.d(TAG, "executeReconnectSequence: Step 0 - Cancel active connectionJob")
                connectionJob?.cancel()
                connectionJob = null

                FileLogger.d(TAG, "executeReconnectSequence: Step 1 - Stop monitoring")
                // 1. Stop monitoring
                stopNetworkMonitoring()
                stopActiveNetworkMonitor()
                statusMonitorJob?.cancel()
                statusMonitorJob = null
                stopProcessWatchdog()
                stopHealthCheck()

                FileLogger.d(TAG, "executeReconnectSequence: Step 2 - Close control socket")
                // 2. Close control socket FIRST (before stopping process)
                try { controlSocket?.close() } catch (_: Exception) {}
                controlSocket = null

                FileLogger.d(TAG, "executeReconnectSequence: Step 3 - Stop process and WAIT")
                // 3. WAIT for process to actually die (sync!)
                tiredvpnProcess?.stopAndWait()
                tiredvpnProcess = null

                // ITERATION 2: Force kill any remaining Go processes to handle long disconnect case
                // This is critical for fixing the 60s+ disconnect bug where process becomes zombie
                FileLogger.d(TAG, "executeReconnectSequence: Step 3b - Force kill any orphan processes")
                NativeProcess.killAllTiredVpnProcesses(applicationInfo.nativeLibraryDir)

                FileLogger.d(TAG, "executeReconnectSequence: Step 4 - Delete control socket file")
                // 4. DELETE control socket file to avoid conflicts
                val controlPath = "${filesDir.absolutePath}/control.sock"
                val socketFile = File(controlPath)
                if (socketFile.exists()) {
                    val deleted = socketFile.delete()
                    FileLogger.d(TAG, "executeReconnectSequence: Control socket file deleted: $deleted")
                }

                FileLogger.d(TAG, "executeReconnectSequence: Step 5 - Close VPN interface")
                // 5. Close VPN interface
                try { vpnInterface?.close() } catch (_: Exception) {}
                vpnInterface = null

                FileLogger.d(TAG, "executeReconnectSequence: Critical cleanup completed, exiting NonCancellable context")
            }

            // 6. Get config first to check connectivity
            val config = ServerRepository.getActiveServer(this@TiredVpnService)
            if (config == null || !config.isValid) {
                FileLogger.e(TAG, "Cannot reconnect - invalid config")
                _state.value = VpnState.Error("Connection lost - invalid config")
                disconnect()
                return
            }

            // 7. Wait for TCP connectivity before reconnecting
            // Skip long waits if we just recovered from network loss
            val skipConnectivityCheck = isNetworkLost
            isNetworkLost = false  // Reset flag

            if (!skipConnectivityCheck) {
                FileLogger.d(TAG, "Reconnect: Step 6 - Checking TCP connectivity to ${config.serverAddress}:${config.serverPort}...")
                _state.value = VpnState.Error("Checking network...")

                var connectivityAttempts = 0
                val maxConnectivityAttempts = 3  // Limit attempts
                while (!checkTcpConnectivity(config.serverAddress, config.serverPort) && connectivityAttempts < maxConnectivityAttempts) {
                    connectivityAttempts++
                    // Shorter backoff: 1s, 2s, 3s (max 3s instead of 30s)
                    val waitMs = minOf(1000L * connectivityAttempts, 3000L)
                    FileLogger.d(TAG, "Reconnect: No connectivity, waiting ${waitMs}ms (attempt $connectivityAttempts/$maxConnectivityAttempts)...")
                    _state.value = VpnState.Error("Waiting for network...")
                    delay(waitMs)
                }
                FileLogger.i(TAG, "Reconnect: TCP connectivity check done after $connectivityAttempts attempts")
            } else {
                FileLogger.i(TAG, "Reconnect: Skipping TCP check - network just recovered")
            }

            // 8. Minimal delay before reconnecting (only on retry attempts)
            val backoffMs = if (reconnectAttempts <= 1) 500L else minOf(1000L * reconnectAttempts, maxBackoffMs)
            FileLogger.d(TAG, "Reconnect: Step 7 - Waiting ${backoffMs}ms before reconnecting...")
            delay(backoffMs)

            // 9. Reconnect
            FileLogger.d(TAG, "Reconnect: Step 8 - Starting reconnection")
            _state.value = VpnState.Error("Reconnecting...")
            FileLogger.i(TAG, "Attempting automatic reconnection...")
            connect(config)
            FileLogger.d(TAG, "DEBUG: Reconnect sequence COMPLETED successfully")
        } catch (e: Exception) {
            FileLogger.e(TAG, "DEBUG: Reconnect sequence FAILED with exception: ${e.message}", e)
            // On failure, schedule retry
            val config = ServerRepository.getActiveServer(this@TiredVpnService)
            if (config != null && config.isValid && _state.value !is VpnState.Disconnected) {
                scheduleAutoReconnect(config)
            }
        }
    }

    private fun establishVpn(config: VpnConfig, tunConfig: TunnelConfig): ParcelFileDescriptor? {
        return try {
            // FIX: Use default IP if "auto" or "0.0.0.0" to avoid VPN interface creation failure
            val effectiveIp = when {
                tunConfig.ip == "auto" -> "10.8.0.2"
                tunConfig.ip == "0.0.0.0" || tunConfig.ip.isNullOrBlank() -> "10.9.0.2"
                else -> tunConfig.ip
            }

            val builder = Builder()
                .setSession("TiredVPN")
                .setMtu(tunConfig.mtu)
                .addAddress(effectiveIp, 24)
                .addRoute("0.0.0.0", 0)  // Route all IPv4
                .addDnsServer(tunConfig.dns)

            // Add backup DNS if different
            if (tunConfig.dns != "8.8.8.8") {
                builder.addDnsServer("8.8.8.8")
            }

            // Apply split tunneling (handles app inclusion/exclusion)
            val usesAllowedApps = applySplitTunneling(builder)

            // Exclude our own app to prevent loops (only if not using addAllowedApplication)
            if (!usesAllowedApps) {
                builder.addDisallowedApplication(packageName)
            }

            // Apply kill switch
            applyKillSwitch(builder)

            builder.establish()
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to establish VPN interface", e)
            null
        }
    }

    /**
     * Apply split tunneling settings.
     * @return true if addAllowedApplication was used (can't mix with addDisallowedApplication)
     */
    private fun applySplitTunneling(builder: Builder): Boolean {
        val prefs = getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
        val mode = prefs.getString("split_tunneling_mode", "exclude") ?: "exclude"
        val selectedApps = prefs.getStringSet("split_tunneling_apps", emptySet()) ?: emptySet()

        FileLogger.i(TAG, "Split tunneling: mode=$mode, apps=${selectedApps.size} (${selectedApps.joinToString()})")

        if (selectedApps.isEmpty()) {
            FileLogger.d(TAG, "No apps selected for split tunneling")
            return false
        }

        when (mode) {
            "exclude" -> {
                // Exclude selected apps from VPN (they bypass VPN)
                for (pkg in selectedApps) {
                    try {
                        builder.addDisallowedApplication(pkg)
                        FileLogger.d(TAG, "Excluded from VPN: $pkg")
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Failed to exclude $pkg: ${e.message}")
                    }
                }
                return false
            }
            "include" -> {
                // Only selected apps use VPN
                // Note: when using addAllowedApplication, we can't use addDisallowedApplication
                for (pkg in selectedApps) {
                    try {
                        builder.addAllowedApplication(pkg)
                        FileLogger.d(TAG, "VPN only for: $pkg")
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Failed to add allowed app $pkg: ${e.message}")
                    }
                }
                return true  // Signal that we used addAllowedApplication
            }
        }
        return false
    }

    private fun applyKillSwitch(builder: Builder) {
        val prefs = getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
        val killSwitchEnabled = prefs.getBoolean("kill_switch", false)

        if (killSwitchEnabled) {
            // Block connections without VPN by setting blocking mode
            // This is handled by the system when VPN disconnects if we set it up properly
            builder.setBlocking(true)
            FileLogger.d(TAG, "Kill switch enabled - blocking mode on")
        }
    }


    private fun disconnect() {
        FileLogger.d(TAG, "Disconnecting VPN")

        // CRITICAL: Set state to Disconnected FIRST to prevent handleControlSocketBroken race condition
        // If we close control socket before setting state, event listener will trigger reconnect
        _state.value = VpnState.Disconnected

        // Mark VPN as disconnected (user action - don't auto-reconnect on boot)
        BootReceiver.markVpnDisconnected(this)

        // Cancel VPN watchdog - user explicitly disconnected
        VpnWatchdogWorker.cancel(this)

        // Cancel any ongoing connection attempt
        connectionJob?.cancel()
        connectionJob = null

        // ITERATION 2: Cancel any pending reconnect jobs to prevent race conditions
        pendingReconnectJob?.cancel()
        pendingReconnectJob = null

        // Reset network lost flag to prevent auto-reconnect
        isNetworkLost = false

        // Stop network monitoring (includes recovery job)
        stopNetworkMonitoring()
        stopActiveNetworkMonitor()

        // Stop status monitoring
        statusMonitorJob?.cancel()
        statusMonitorJob = null

        // Stop process watchdog
        stopProcessWatchdog()

        // ITERATION 3: Stop health check
        stopHealthCheck()

        // Stop protect server
        stopProtectServer()

        // Send disconnect command before closing socket
        sendDisconnectCommand()

        // Close control socket
        try {
            controlSocket?.close()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Error closing control socket", e)
        }
        controlSocket = null

        // Stop tiredvpn process (now waits internally for up to 5 seconds)
        try {
            FileLogger.d(TAG, "Stopping tiredvpn process...")
            tiredvpnProcess?.stop()
            FileLogger.d(TAG, "Process stopped")
        } catch (e: Exception) {
            FileLogger.w(TAG, "Error stopping process", e)
        }
        tiredvpnProcess = null

        // Kill any orphan tiredvpn processes that might have leaked
        NativeProcess.killAllTiredVpnProcesses(applicationInfo.nativeLibraryDir)

        // Close VPN interface
        try {
            vpnInterface?.close()
            FileLogger.d(TAG, "VPN interface closed")
        } catch (e: Exception) {
            FileLogger.w(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        // Release WakeLock
        releaseWakeLock()

        // Reset coroutine scope to release any blocked Dispatchers.IO threads.
        // Without this, a stuck connection leaves dead threads in the IO pool,
        // and subsequent scope.launch calls queue coroutines that never execute.
        resetScope()

        // State already set to Disconnected at the beginning of disconnect()
        // CRITICAL: Call stopForeground first, THEN stopSelf to ensure proper cleanup
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Error stopping foreground", e)
        }

        // stopSelf() should clear VPN from system
        try {
            stopSelf()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Error stopping service", e)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TiredVPN::VpnServiceLock"
            )
        }
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
                FileLogger.d(TAG, "WakeLock acquired")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                FileLogger.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TiredVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        FileLogger.i(TAG, "=== SERVICE onDestroy() ===")
        // disconnect() resets the scope internally (resetScope()), so no separate scope.cancel() needed
        disconnect()

        // Kill any remaining orphan processes
        NativeProcess.killAllTiredVpnProcesses(applicationInfo.nativeLibraryDir)

        // Final scope cancellation for safety (in case disconnect() was already called)
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        FileLogger.w(TAG, "VPN permission revoked")
        disconnect()
        super.onRevoke()
    }

    data class TunnelConfig(
        val ip: String,
        val serverIp: String,
        val dns: String,
        val mtu: Int,
        val routes: String
    )

    /**
     * Parse connection info from log lines.
     * Format: "Connected via Traffic Morph (Yandex Video) (latency=131.708456ms, attempts=9, rtt_masking=false)"
     */
    private fun parseConnectionInfo(line: String) {
        // Match "Connected via STRATEGY_NAME (latency=XXXms, attempts=N"
        val regex = Regex("""Connected via (.+?) \(latency=([0-9.]+)ms, attempts=(\d+)""")
        val match = regex.find(line)
        if (match != null) {
            connectedStrategy = match.groupValues[1]
            connectedLatencyMs = match.groupValues[2].toDoubleOrNull()?.toLong() ?: 0
            connectedAttempts = match.groupValues[3].toIntOrNull() ?: 1
            FileLogger.i(TAG, "Parsed connection info: strategy=$connectedStrategy, latency=${connectedLatencyMs}ms, attempts=$connectedAttempts")
        }
        // Note: Keepalive tracking moved to Go side - events sent via control socket
    }

    /**
     * Check TCP connectivity to server before attempting reconnect.
     * This avoids wasting time on full reconnect attempts when network is down.
     * IMPORTANT: Socket must be protected to bypass VPN and use physical network.
     */
    private suspend fun checkTcpConnectivity(host: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = java.net.Socket()
                // CRITICAL: Protect socket so it bypasses the (dead) VPN tunnel
                // and goes through the physical network
                if (!protect(socket)) {
                    FileLogger.w(TAG, "TCP connectivity check: failed to protect socket")
                }
                socket.soTimeout = 3000  // Reduced from 5s to 3s
                val address = java.net.InetSocketAddress(host, port)
                socket.connect(address, 3000) // 3 second timeout (reduced from 5s)
                socket.close()
                FileLogger.d(TAG, "TCP connectivity check passed: $host:$port")
                true
            } catch (e: Exception) {
                FileLogger.d(TAG, "TCP connectivity check failed: $host:$port - ${e.message}")
                false
            }
        }
    }

    fun checkTunnelHealth(): Boolean {
        if (_state.value !is VpnState.Connected) return true
        if (lastKeepaliveTime == 0L) return true // Not initialized yet

        val timeSinceKeepalive = System.currentTimeMillis() - lastKeepaliveTime
        if (timeSinceKeepalive > keepaliveTimeoutMs) {
            FileLogger.e(TAG, "Tunnel appears dead - no keepalive for ${timeSinceKeepalive}ms")
            return false
        }
        return true
    }
}
