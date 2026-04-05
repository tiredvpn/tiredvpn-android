package com.tiredvpn.android.native

import android.os.Build
import com.tiredvpn.android.util.FileLogger
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NativeProcess implementation using JNI to avoid PhantomProcess killer on Android 12+.
 * Compatible interface with NativeProcess but uses TiredVpnNative instead of Runtime.exec().
 */
class NativeProcessJNI(
    private val args: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workingDir: String? = null,
    private val onOutput: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onExit: (Int) -> Unit = {}
) : TiredVpnProcess, TiredVpnNative.NativeCallback {
    companion object {
        private const val TAG = "NativeProcessJNI"
    }

    private var isStarted = false
    private val onExitCalled = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val isRunning: Boolean
        get() = isStarted

    override fun start() {
        if (isRunning) {
            FileLogger.w(TAG, "Process already running (JNI)")
            return
        }

        try {
            FileLogger.i(TAG, "=== STARTING NATIVE PROCESS (JNI MODE) ===")
            FileLogger.i(TAG, "Command: ${args.joinToString(" ")}")
            FileLogger.i(TAG, "Android ${Build.VERSION.SDK_INT} - Using JNI to avoid PhantomProcess kill")

            // Initialize JNI with this callback
            TiredVpnNative.initialize(this)

            // Build args string (skip "client" if it's the first arg)
            val argsStr = if (args.firstOrNull() == "client") {
                args.drop(1).joinToString(" ")
            } else {
                args.joinToString(" ")
            }

            FileLogger.i(TAG, "Starting with args: $argsStr")

            // Start the client
            val result = TiredVpnNative.start(argsStr)
            if (result != 0) {
                FileLogger.e(TAG, "Failed to start JNI client: code=$result")
                onExit(result)
                return
            }

            isStarted = true
            FileLogger.i(TAG, "JNI client started successfully")

        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start JNI process", e)
            onExit(1)
        }
    }

    override fun stop() {
        if (!isRunning) {
            FileLogger.w(TAG, "Process not running (JNI)")
            return
        }

        try {
            FileLogger.i(TAG, "Stopping JNI client")
            TiredVpnNative.stop()
            isStarted = false
            if (onExitCalled.compareAndSet(false, true)) onExit(0)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error stopping JNI process", e)
        }

        scope.cancel()
    }

    override suspend fun stopAndWait(): Boolean {
        stop()
        // JNI version stops synchronously, no need to wait
        return true
    }

    fun waitFor(): Int {
        // JNI version doesn't block, return 0 for success
        return if (isStarted) 0 else 1
    }

    // TiredVpnNative.NativeCallback implementation

    override fun onStateChange(state: String, jsonData: String) {
        FileLogger.i(TAG, "State change: $state")
        // Forward state changes as log output
        onOutput("[STATE] $state: $jsonData")

        when (state) {
            "connected" -> {
                try {
                    val json = JSONObject(jsonData)
                    val strategy = json.optString("strategy", "unknown")
                    val latency = json.optLong("latency_ms", 0)
                    onOutput("Connected via $strategy (${latency}ms)")
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Error parsing connected state", e)
                }
            }
            "error" -> {
                try {
                    val json = JSONObject(jsonData)
                    val error = json.optString("error", "Unknown error")
                    onError("Error: $error")
                } catch (e: Exception) {
                    onError("Error: $jsonData")
                }
            }
            "disconnected" -> {
                isStarted = false
                if (onExitCalled.compareAndSet(false, true)) onExit(0)
            }
        }
    }

    override fun onLogMessage(message: String) {
        onOutput(message)
    }
}
