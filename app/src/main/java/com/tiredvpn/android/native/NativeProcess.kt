package com.tiredvpn.android.native

import android.annotation.SuppressLint
import android.os.Build
import com.tiredvpn.android.util.FileLogger
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

// Process.isAlive() and destroyForcibly() require API 26
// Below API 26: isAlive assumes alive if non-null; destroyForcibly falls back to destroy()
private val Process.isAliveCompat: Boolean
    @SuppressLint("NewApi")
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isAlive else true

@SuppressLint("NewApi")
private fun Process.destroyForciblyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}

class NativeProcess(
    private val args: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workingDir: String? = null,
    private val onOutput: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val onExit: (Int) -> Unit = {}
) : TiredVpnProcess {
    companion object {
        private const val TAG = "NativeProcess"

        /**
         * Kill all running tiredvpn processes.
         * This is critical to avoid process leaks during reconnects.
         * Android doesn't allow pkill, so we use /proc filesystem to find and kill processes.
         */
        fun killAllTiredVpnProcesses(nativeLibDir: String) {
            FileLogger.i(TAG, "=== KILLING ALL TIREDVPN PROCESSES ===")
            try {
                val myPid = android.os.Process.myPid()
                val myUid = android.os.Process.myUid()
                val procDir = java.io.File("/proc")
                var killed = 0

                // Iterate through all /proc/[pid] directories
                procDir.listFiles()?.forEach { pidDir ->
                    val pid = pidDir.name.toIntOrNull() ?: return@forEach
                    if (pid == myPid) return@forEach  // Don't kill ourselves

                    try {
                        // Check if this process belongs to us (same UID)
                        val statusFile = java.io.File(pidDir, "status")
                        if (!statusFile.exists()) return@forEach

                        val statusContent = statusFile.readText()
                        val uidLine = statusContent.lines().find { it.startsWith("Uid:") }
                        val processUid = uidLine?.split("\\s+".toRegex())?.getOrNull(1)?.toIntOrNull()

                        if (processUid != myUid) return@forEach  // Not our process

                        // Check if this is libtiredvpn.so
                        val cmdlineFile = java.io.File(pidDir, "cmdline")
                        if (!cmdlineFile.exists()) return@forEach

                        val cmdline = cmdlineFile.readText()
                        if (cmdline.contains("libtiredvpn.so") || cmdline.contains("tiredvpn")) {
                            FileLogger.w(TAG, "Killing orphan tiredvpn process: pid=$pid, cmd=$cmdline")
                            android.os.Process.killProcess(pid)
                            killed++
                        }
                    } catch (e: Exception) {
                        // Ignore - process might have died or we don't have permission
                    }
                }

                FileLogger.i(TAG, "Killed $killed orphan tiredvpn processes")
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to kill orphan processes", e)
            }
        }
    }

    private var process: Process? = null
    private var outputJob: Job? = null
    private var errorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val isRunning: Boolean
        get() = process?.isAliveCompat == true

    override fun start() {
        if (isRunning) {
            FileLogger.w(TAG, "Process already running")
            return
        }

        try {
            // CRITICAL FIX: Android 12+ PhantomProcess killer fix
            // Use Runtime.exec() with custom environment that sets process group
            // This prevents the process from being killed as a "phantom process"
            val envList = mutableListOf<String>()

            // Copy system environment
            System.getenv().forEach { (key, value) ->
                envList.add("$key=$value")
            }

            // Add custom environment variables
            env.forEach { (key, value) ->
                envList.add("$key=$value")
            }

            // Add ANDROID_ROOT if not set (required for some devices)
            if (!envList.any { it.startsWith("ANDROID_ROOT=") }) {
                envList.add("ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: "/system"}")
            }

            FileLogger.i(TAG, "=== STARTING NATIVE PROCESS ===")
            FileLogger.i(TAG, "Command: ${args.joinToString(" ")}")

            // CRITICAL FIX: Android 10+ prevents direct execution of binaries from app storage
            // Use shell wrapper to bypass this restriction
            // Instead of: /path/to/binary arg1 arg2
            // We do: /system/bin/sh -c "/path/to/binary arg1 arg2"
            val shellArgs = mutableListOf("/system/bin/sh", "-c")
            val cmdLine = args.joinToString(" ") { arg ->
                // Quote arguments that contain spaces
                if (arg.contains(" ")) "\"$arg\"" else arg
            }
            shellArgs.add(cmdLine)

            FileLogger.i(TAG, "Shell wrapper command: ${shellArgs.joinToString(" ")}")

            // Use Runtime.exec() instead of ProcessBuilder
            // This gives us more control over the process lifecycle
            process = Runtime.getRuntime().exec(
                shellArgs.toTypedArray(),
                envList.toTypedArray(),
                workingDir?.let { java.io.File(it) }
            )

            FileLogger.i(TAG, "Process started, pid=${process?.toString()}")

            // Read stdout
            outputJob = scope.launch {
                FileLogger.d(TAG, "stdout reader started")
                try {
                    BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            if (isActive) {
                                FileLogger.d(TAG, "[stdout] $line")
                                onOutput(line)
                            }
                        }
                    }
                    FileLogger.d(TAG, "stdout reader finished (stream closed)")
                } catch (e: Exception) {
                    if (isActive) FileLogger.e(TAG, "Error reading stdout", e)
                }
            }

            // Read stderr
            errorJob = scope.launch {
                FileLogger.d(TAG, "stderr reader started")
                try {
                    BufferedReader(InputStreamReader(process!!.errorStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            if (isActive) {
                                FileLogger.e(TAG, "[stderr] $line")
                                onError(line)
                            }
                        }
                    }
                    FileLogger.d(TAG, "stderr reader finished (stream closed)")
                } catch (e: Exception) {
                    if (isActive) FileLogger.e(TAG, "Error reading stderr", e)
                }
            }

            // Monitor exit
            scope.launch {
                try {
                    val exitCode = process!!.waitFor()
                    FileLogger.w(TAG, "=== PROCESS EXITED === code=$exitCode")
                    onExit(exitCode)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Error waiting for process", e)
                }
            }

        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start process", e)
            throw e
        }
    }

    override fun stop() {
        FileLogger.d(TAG, "Stopping process (async)")

        outputJob?.cancel()
        errorJob?.cancel()

        process?.let { proc ->
            try {
                // Try graceful shutdown first
                proc.destroy()

                // Give it 2 seconds to terminate
                scope.launch {
                    delay(2000)
                    if (proc.isAliveCompat) {
                        FileLogger.w(TAG, "Force killing process")
                        proc.destroyForciblyCompat()
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error stopping process", e)
            }
        }

        process = null
    }

    /**
     * Stop the process and wait for it to actually die.
     * This is a suspend function that blocks until the process is dead.
     * @return true if process is dead, false if still alive after timeout
     */
    override suspend fun stopAndWait(): Boolean {
        FileLogger.d(TAG, "Stopping process and waiting...")

        outputJob?.cancel()
        errorJob?.cancel()

        val proc = process
        if (proc != null) {
            try {
                // Try graceful shutdown first
                proc.destroy()

                // Wait up to 3 seconds for process to die
                val deadline = System.currentTimeMillis() + 3000
                while (proc.isAliveCompat && System.currentTimeMillis() < deadline) {
                    delay(100)
                }

                if (proc.isAliveCompat) {
                    FileLogger.w(TAG, "Force killing process after graceful timeout")
                    proc.destroyForciblyCompat()
                    // Wait a bit more after force kill
                    delay(500)
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error stopping process", e)
            }
        }

        val isDead = process?.isAliveCompat != true
        process = null
        FileLogger.d(TAG, "Process stopped: isDead=$isDead")
        return isDead
    }

    fun sendInput(data: String) {
        process?.outputStream?.let { stream ->
            try {
                stream.write(data.toByteArray())
                stream.flush()
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error sending input", e)
            }
        }
    }
}
