package com.tiredvpn.android.native

import com.tiredvpn.android.util.FileLogger

/**
 * JNI bridge to Go TiredVPN client.
 * This replaces ProcessBuilder-based execution to avoid PhantomProcess killer on Android 12+.
 *
 * Architecture:
 * - Go code runs in the same process as Android app (no fork/exec)
 * - State changes and logs are delivered via callbacks
 * - TUN fd is passed directly through JNI
 * - No Unix socket IPC needed
 */
object TiredVpnNative {
    private const val TAG = "TiredVpnNative"
    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("tiredvpn")
            isLibraryLoaded = true
            FileLogger.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            isLibraryLoaded = false
            FileLogger.w(TAG, "Native library not available (JNI mode disabled): ${e.message}")
            // Don't throw - allow app to continue with ProcessBuilder mode
        }
    }

    // JNI native methods
    private external fun initNative(callback: NativeCallback)
    private external fun cleanupNative()
    private external fun startClient(args: String): Int
    private external fun stopClient()
    private external fun setTunFd(fd: Int)
    private external fun getTunFd(): Int
    private external fun sendCommand(cmd: String): String

    // Callbacks from Go
    interface NativeCallback {
        fun onStateChange(state: String, jsonData: String)
        fun onLogMessage(message: String)
    }

    private var callback: NativeCallback? = null

    /**
     * Initialize native library with callback.
     * Must be called before any other methods.
     */
    fun initialize(cb: NativeCallback) {
        callback = cb
        initNative(callbackProxy)
        FileLogger.i(TAG, "Native library initialized with callback")
    }

    /**
     * Cleanup native library.
     * Should be called when service is destroyed.
     */
    fun cleanup() {
        cleanupNative()
        callback = null
        FileLogger.i(TAG, "Native library cleaned up")
    }

    /**
     * Start VPN client with command line arguments.
     *
     * @param args Space-separated command line arguments (without "client" command)
     *             Example: "-server host:port -secret xxx -tun -tun-ip auto"
     * @return 0 on success, non-zero on error
     */
    fun start(args: String): Int {
        FileLogger.i(TAG, "Starting client with args: $args")
        return startClient(args)
    }

    /**
     * Stop VPN client gracefully.
     */
    fun stop() {
        FileLogger.i(TAG, "Stopping client")
        stopClient()
    }

    /**
     * Set TUN file descriptor for Go to use.
     *
     * @param fd File descriptor from ParcelFileDescriptor.getFd()
     */
    fun setTunFileDescriptor(fd: Int) {
        FileLogger.i(TAG, "Setting TUN fd: $fd")
        setTunFd(fd)
    }

    /**
     * Get current TUN file descriptor.
     *
     * @return Current TUN fd or -1 if not set
     */
    fun getTunFileDescriptor(): Int {
        return getTunFd()
    }

    /**
     * Send command to running client (JSON).
     *
     * @param cmd JSON command string
     * @return JSON response string
     */
    fun command(cmd: String): String {
        FileLogger.d(TAG, "Sending command: $cmd")
        val response = sendCommand(cmd)
        FileLogger.d(TAG, "Response: $response")
        return response
    }

    // Callback proxy that forwards to registered callback
    private val callbackProxy = object : NativeCallback {
        override fun onStateChange(state: String, jsonData: String) {
            FileLogger.i(TAG, "State change: $state, data: $jsonData")
            callback?.onStateChange(state, jsonData)
        }

        override fun onLogMessage(message: String) {
            FileLogger.d(TAG, "[native] $message")
            callback?.onLogMessage(message)
        }
    }
}
