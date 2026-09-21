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

    @Volatile
    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("tiredvpn")
            isLibraryLoaded = true
            FileLogger.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            isLibraryLoaded = false
            FileLogger.w(TAG, "Native library not available (JNI mode disabled): ${e.message}")
            // Don't throw - allow the caller to report a usable error instead
            // of the process dying on the first external call.
        }
    }

    /**
     * Whether libtiredvpn.so was loaded for this device's ABI.
     *
     * Read before any external call. The flag used to be written and never
     * looked at, so a device without a matching .so — or a build whose Go
     * exports drifted from these declarations — went straight into an
     * `external fun`, and UnsatisfiedLinkError is an Error, which the
     * `catch (e: Exception)` around the call site does not catch. The app died
     * instead of reporting a missing core.
     */
    val isAvailable: Boolean get() = isLibraryLoaded

    // JNI native methods
    private external fun initNative(callback: NativeCallback)
    private external fun cleanupNative()
    private external fun startClient(args: Array<String>): Int
    private external fun stopClient()
    private external fun setTunFd(fd: Int)

    // Callbacks from Go
    interface NativeCallback {
        fun onStateChange(state: String, jsonData: String)
        fun onLogMessage(message: String)
    }

    @Volatile
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
     *
     * Safe to call when the library never loaded: the guard is what keeps an
     * UnsatisfiedLinkError — an Error, not an Exception — out of the
     * `catch (e: Exception)` blocks that surround the call sites.
     */
    fun cleanup() {
        if (!isLibraryLoaded) {
            callback = null
            return
        }
        cleanupNative()
        callback = null
        FileLogger.i(TAG, "Native library cleaned up")
    }

    /**
     * Start VPN client with command line arguments.
     *
     * Args are passed as a real string array across the JNI boundary (one token
     * per element), so values containing spaces (e.g. a "morph_Yandex Video"
     * strategy ID) survive intact. The "client" command must already be stripped.
     *
     * @param args Argv tokens without the "client" command.
     *             Example: ["-server", "host:port", "-secret", "xxx", "-tun"]
     * @return 0 on success, non-zero on error
     */
    fun start(args: Array<String>): Int {
        FileLogger.i(TAG, "Starting client with ${args.size} args: ${NativeArgs.redact(args)}")
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
     * Stop whatever core is still running and drop the JNI callback, so a
     * fresh [initialize] cannot have the previous core's death delivered to
     * the new callback.
     *
     * Why it is needed: `startClient` on the Go side cancels the running
     * client but does NOT wait for its goroutine, while `stopClient` waits up
     * to five seconds and only then emits "disconnected". Without the wait, the
     * old goroutine's terminal `sendStateChange` arrives after `initNative`
     * has already replaced the global callback reference — and the new
     * NativeProcessJNI marks itself exited on a message about its predecessor.
     */
    fun reset() {
        if (!isLibraryLoaded) return
        try {
            stopClient()
        } catch (e: Throwable) {
            FileLogger.w(TAG, "reset: stopClient failed: ${e.message}")
        }
        try {
            cleanupNative()
        } catch (e: Throwable) {
            FileLogger.w(TAG, "reset: cleanupNative failed: ${e.message}")
        }
        callback = null
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
