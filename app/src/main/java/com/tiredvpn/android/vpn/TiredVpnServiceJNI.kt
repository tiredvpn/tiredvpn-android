package com.tiredvpn.android.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.tiredvpn.android.R
import com.tiredvpn.android.TiredVpnApp
import com.tiredvpn.android.native.TiredVpnNative
import com.tiredvpn.android.ui.MainActivity
import com.tiredvpn.android.util.FileLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/**
 * VpnService using JNI integration instead of ProcessBuilder.
 * This avoids PhantomProcess killer on Android 12+.
 *
 * Key differences from original TiredVpnService:
 * - No NativeProcess (no fork/exec)
 * - No control socket (direct JNI calls)
 * - No protect socket (VpnService.protect() called directly via JNI callback)
 * - State changes via JNI callbacks
 */
class TiredVpnServiceJNI : VpnService(), TiredVpnNative.NativeCallback {

    companion object {
        private const val TAG = "TiredVpnServiceJNI"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        const val ACTION_CONNECT = "com.tiredvpn.android.CONNECT"
        const val ACTION_DISCONNECT = "com.tiredvpn.android.DISCONNECT"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectionJob: Job? = null
    private var protectServerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "Service created")

        // Initialize native library with callback
        try {
            TiredVpnNative.initialize(this)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to initialize native library", e)
            _state.value = VpnState.Error("Failed to load native library: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FileLogger.i(TAG, "Service destroyed")
        disconnect()
        TiredVpnNative.cleanup()
        scope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = ServerRepository.getActiveServer(this)
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

    private fun connect(config: VpnConfig) {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            try {
                _state.value = VpnState.Connecting

                FileLogger.i(TAG, "=== STARTING VPN CONNECTION ===")
                FileLogger.i(TAG, "Server: ${config.serverAddress}:${config.serverPort}")
                FileLogger.i(TAG, "Strategy: ${config.strategy}")

                // Step 0: Start protect socket server BEFORE VPN interface
                val protectPath = "${filesDir.absolutePath}/protect.sock"
                FileLogger.i(TAG, "STEP 0: Starting protect socket server...")
                startProtectServer(protectPath)
                FileLogger.i(TAG, "STEP 0: Protect server started")

                // Step 1: Build VPN interface
                val builder = Builder()
                    .setSession("TiredVPN")
                    .setMtu(1500)
                    .addAddress("10.9.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")

                // Split tunneling
                // TODO: Implement split tunneling with new config structure
                // applyAllowedApps(builder, config)
                // applyDisallowedApps(builder, config)

                // Create VPN interface
                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    throw Exception("Failed to establish VPN interface")
                }

                val tunFd = vpnInterface!!.fd
                FileLogger.i(TAG, "VPN interface created: fd=$tunFd")

                // Step 2: Pass TUN fd to native code
                TiredVpnNative.setTunFileDescriptor(tunFd)

                // Step 3: Build command line arguments
                val serverEndpoint = "${config.serverAddress}:${config.serverPort}"
                val args = buildArgs(config, serverEndpoint, protectPath)

                FileLogger.i(TAG, "Starting native client with args: $args")

                // Step 4: Start native client (non-blocking)
                val result = TiredVpnNative.start(args)
                if (result != 0) {
                    throw Exception("Failed to start native client: code=$result")
                }

                FileLogger.i(TAG, "Native client started successfully")

                // State will be updated via onStateChange() callback

            } catch (e: Exception) {
                FileLogger.e(TAG, "Connection failed", e)
                _state.value = VpnState.Error(e.message ?: "Unknown error")
                disconnect()
            }
        }
    }

    private fun disconnect() {
        FileLogger.i(TAG, "=== DISCONNECTING VPN ===")

        connectionJob?.cancel()
        connectionJob = null

        // Stop protect server
        stopProtectServer()

        // Stop native client
        try {
            TiredVpnNative.stop()
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error stopping native client", e)
        }

        // Close VPN interface
        vpnInterface?.let {
            try {
                it.close()
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error closing VPN interface", e)
            }
        }
        vpnInterface = null

        _state.value = VpnState.Disconnected
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildArgs(config: VpnConfig, serverEndpoint: String, protectPath: String): String {
        val args = mutableListOf<String>()

        // Server
        args.add("-server")
        args.add(serverEndpoint)

        // Secret
        args.add("-secret")
        args.add(config.secret)

        // Protect socket path for VpnService.protect()
        args.add("-protect-path")
        args.add(protectPath)

        // TUN mode
        args.add("-tun")
        args.add("-tun-ip")
        args.add("auto")

        // Android mode
        args.add("-android")

        // Strategy (optional)
        if (config.strategy.isNotBlank() && config.strategy != "auto") {
            args.add("-strategy")
            args.add(config.strategy)
        }

        // Cover host (optional)
        if (config.coverHost.isNotBlank()) {
            args.add("-cover-host")
            args.add(config.coverHost)
        }

        return args.joinToString(" ")
    }

    // TODO: Implement split tunneling functions with new config structure
    /*
    private fun applyAllowedApps(builder: Builder, config: VpnConfig) {
        config.allowedApps.forEach { packageName ->
            try {
                builder.addAllowedApplication(packageName)
                FileLogger.d(TAG, "Allowed app: $packageName")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to allow app: $packageName", e)
            }
        }
    }

    private fun applyDisallowedApps(builder: Builder, config: VpnConfig) {
        config.disallowedApps.forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
                FileLogger.d(TAG, "Disallowed app: $packageName")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to disallow app: $packageName", e)
            }
        }
    }
    */

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TiredVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TiredVPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // TiredVpnNative.NativeCallback implementation

    override fun onStateChange(state: String, jsonData: String) {
        FileLogger.i(TAG, "Native state change: $state, data: $jsonData")

        try {
            when (state) {
                "connecting" -> {
                    _state.value = VpnState.Connecting
                    updateNotification("Connecting...")
                }
                "connected" -> {
                    val json = JSONObject(jsonData)
                    val strategy = json.optString("strategy", "unknown")
                    val latency = json.optLong("latency_ms", 0)
                    val ip = json.optString("ip", "unknown")

                    _state.value = VpnState.Connected(strategy, latency)
                    updateNotification("Connected via $strategy (${latency}ms)")

                    FileLogger.i(TAG, "=== VPN CONNECTED ===")
                    FileLogger.i(TAG, "Strategy: $strategy")
                    FileLogger.i(TAG, "Latency: ${latency}ms")
                    FileLogger.i(TAG, "IP: $ip")
                }
                "error" -> {
                    val json = JSONObject(jsonData)
                    val error = json.optString("error", "Unknown error")

                    _state.value = VpnState.Error(error)
                    updateNotification("Error: $error")

                    FileLogger.e(TAG, "Native error: $error")
                }
                "disconnected" -> {
                    // Just update state - don't call disconnect() to avoid recursion
                    // disconnect() will be called from UI button or onDestroy()
                    _state.value = VpnState.Disconnected
                    updateNotification("Disconnected")
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error parsing state change", e)
        }
    }

    override fun onLogMessage(message: String) {
        FileLogger.d(TAG, "[native] $message")
    }

    // Protect Socket Server implementation

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
        val byteBuf = ByteBuffer.wrap(fdBytes)
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
            android.system.Os.write(clientFd, ByteBuffer.wrap(byteArrayOf(0)))
        } else {
            FileLogger.w(TAG, "Failed to protect fd=$fd")
            android.system.Os.write(clientFd, ByteBuffer.wrap(byteArrayOf(1)))
        }
    }

    private fun stopProtectServer() {
        protectServerJob?.cancel()
        protectServerJob = null
    }
}
