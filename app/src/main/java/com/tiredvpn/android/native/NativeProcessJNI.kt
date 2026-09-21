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

        /**
         * Exit code for "there is no core to run on this device": the .so is
         * missing for this ABI, or its exports no longer match the `external
         * fun` declarations. Retrying cannot help, so the service must not
         * treat it as a transient failure.
         */
        const val EXIT_NO_NATIVE_LIBRARY = 127
    }

    @Volatile
    private var isStarted = false
    private val onExitCalled = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * True from a successful [start] until the core reports that it left.
     *
     * Honest about its limits: the core runs in this process, so this says
     * "started and has not announced an exit", not "the tunnel works". A
     * wedged goroutine or a looping handshake leaves it true. The service pairs
     * it with its own control-channel liveness check.
     */
    override val isRunning: Boolean
        get() = isStarted

    override fun start() {
        if (isRunning) {
            FileLogger.w(TAG, "Process already running (JNI)")
            return
        }

        if (!TiredVpnNative.isAvailable) {
            // Degrade to a reportable error instead of dying on the first
            // external call: UnsatisfiedLinkError is an Error, so the
            // catch (e: Exception) below would let it take the process down.
            // Null-safe on purpose: this is the degradation path, and it must
            // not itself throw. Build.SUPPORTED_ABIS is a platform type.
            val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
            FileLogger.e(TAG, "Native core library is not available (abi=$abi)")
            onError("Native core library missing for this device (abi=$abi)")
            if (onExitCalled.compareAndSet(false, true)) onExit(EXIT_NO_NATIVE_LIBRARY)
            return
        }

        try {
            FileLogger.i(TAG, "=== STARTING NATIVE PROCESS (JNI MODE) ===")
            FileLogger.i(TAG, "Command: ${NativeArgs.redact(args)}")
            FileLogger.i(TAG, "Android ${Build.VERSION.SDK_INT} - Using JNI to avoid PhantomProcess kill")

            // Drain any core left over from a previous attempt BEFORE claiming
            // the callback. startClient() cancels the old client without
            // waiting for its goroutine, so its terminal state change would
            // otherwise be delivered to us and mark this instance dead. See
            // TiredVpnNative.reset.
            TiredVpnNative.reset()

            // Initialize JNI with this callback
            TiredVpnNative.initialize(this)

            // Build args array (skip "client" if it's the first arg). Passed as a
            // real String[] across JNI — no space-joining — so arg values that
            // contain spaces (e.g. "morph_Yandex Video") are preserved.
            val argv = if (args.firstOrNull() == "client") {
                args.drop(1)
            } else {
                args
            }.toTypedArray()

            FileLogger.i(TAG, "Starting with args: ${NativeArgs.redact(argv)}")

            // Start the client
            val result = TiredVpnNative.start(argv)
            if (result != 0) {
                FileLogger.e(TAG, "Failed to start JNI client: code=$result")
                onExit(result)
                return
            }

            isStarted = true
            FileLogger.i(TAG, "JNI client started successfully")

        } catch (e: LinkageError) {
            // The .so loaded but an export is missing or its signature drifted
            // from the declarations in TiredVpnNative. An Error, so it walks
            // straight past catch (e: Exception) and kills the app.
            FileLogger.e(TAG, "JNI boundary mismatch: ${e.message}")
            onError("Native core is incompatible with this build: ${e.message}")
            if (onExitCalled.compareAndSet(false, true)) onExit(EXIT_NO_NATIVE_LIBRARY)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start JNI process", e)
            if (onExitCalled.compareAndSet(false, true)) onExit(1)
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
        } catch (e: LinkageError) {
            FileLogger.e(TAG, "JNI boundary mismatch while stopping: ${e.message}")
            isStarted = false
            if (onExitCalled.compareAndSet(false, true)) onExit(EXIT_NO_NATIVE_LIBRARY)
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
                // Terminal, exactly like "disconnected": the Go side sends
                // this from the deferred tail of the client goroutine (and
                // from its recover()), so by the time it arrives the core has
                // stopped. Leaving isStarted true here is what made every
                // liveness check in the service report a running core after it
                // had died — watchdog, health check, network-change handler
                // and the two connect waits all read this one flag.
                markExited(1)
            }
            "disconnected" -> {
                markExited(0)
            }
        }
    }

    /**
     * Record that the core has gone, once.
     *
     * The `isStarted` guard makes this idempotent for *this* instance, and
     * that is all it does. It is not a filter against cross-talk, whatever it
     * said here before: there is one global callback object on the JNI side
     * (`g_callback_obj`), so a superseded core's terminal callback arriving
     * after a new instance has registered is delivered to the new instance,
     * where `isStarted` is true and the message goes straight through —
     * marking a healthy core dead.
     *
     * Closing that needs an instance number carried through `initNative` and
     * handed back with the callback, i.e. a change on the Go side. Until then
     * the mitigation is ordering: [TiredVpnNative.reset] and the `cleanup()`
     * on every teardown path drop the bridge before the next `initialize`,
     * which leaves a late callback with nowhere to land as long as the two do
     * not overlap.
     */
    private fun markExited(code: Int) {
        if (!isStarted) {
            FileLogger.w(TAG, "Ignoring terminal state for a core this instance never started (code=$code)")
            return
        }
        isStarted = false
        if (onExitCalled.compareAndSet(false, true)) onExit(code)
    }

    override fun onLogMessage(message: String) {
        onOutput(message)
    }
}
