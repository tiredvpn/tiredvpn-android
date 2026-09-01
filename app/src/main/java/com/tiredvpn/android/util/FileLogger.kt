package com.tiredvpn.android.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_FILE_NAME = "tiredvpn.log"
    private const val MAX_FILE_SIZE = 1_000_000L // 1 MB

    private var logFile: File? = null
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val isRunning = AtomicBoolean(false)
    private var writerThread: Thread? = null

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        init(File(context.filesDir, LOG_FILE_NAME))
    }

    /** Target file as an explicit parameter, so the writer can be tested without a Context. */
    internal fun init(file: File) {
        logFile = file
        closeWriter()
        startWriterThread()
        log("I", TAG, "=== FileLogger initialized ===")
    }

    private fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val logLine = buildString {
            append(timestamp)
            append(" ")
            append(level)
            append("/")
            append(tag)
            append(": ")
            append(message)
            if (throwable != null) {
                append("\n")
                append(Log.getStackTraceString(throwable))
            }
        }
        logQueue.offer(logLine)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        log("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        log("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, message, throwable)
        else Log.w(tag, message)
        log("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable)
        else Log.e(tag, message)
        log("E", tag, message, throwable)
    }

    private fun startWriterThread() {
        if (isRunning.getAndSet(true)) return

        writerThread = thread(name = "FileLogger-Writer", isDaemon = true) {
            while (isRunning.get()) {
                try {
                    writeQueuedLogs()
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in writer thread", e)
                }
            }
        }
    }

    /**
     * The writer is held open across flushes instead of being reopened on each
     * pass. The old code built a FileOutputStream ten times a second, which
     * made this thread by far the most frequent caller of open() in the
     * process — and therefore the one that inherited any descriptor number
     * freed behind its owner's back, aborting on the fdsan ownership check.
     * The double close it tripped over lived in the TUN cleanup, not here, but
     * opening two orders of magnitude less often removes this thread as the
     * standing target and cuts the wakeup cost of idle logging.
     */
    private var writer: OutputStreamWriter? = null

    private fun openWriter(file: File): OutputStreamWriter? =
        writer ?: try {
            OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).also { writer = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open log file", e)
            null
        }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close log file", e)
        }
        writer = null
    }

    private fun writeQueuedLogs() {
        val file = logFile ?: return
        if (logQueue.isEmpty()) return

        try {
            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                // Dropped before rotation on purpose, but not because the held
                // handle would corrupt anything: the stream is opened in append
                // mode, so each write lands at the current end even after
                // rotateLog truncates. Breaking this on purpose does not turn
                // any test red, and it should not be read as load-bearing — it
                // only keeps the invariant if the open mode ever changes.
                closeWriter()
                rotateLog(file)
            }

            val out = openWriter(file) ?: return
            var line: String?
            var count = 0
            while (logQueue.poll().also { line = it } != null && count < 100) {
                out.appendLine(line)
                count++
            }
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write logs", e)
            // A broken handle must not be reused: the file may have been
            // rotated or deleted from under us.
            closeWriter()
        }
    }

    private fun rotateLog(file: File) {
        try {
            Log.i(TAG, "Rotating log file (size: ${file.length()})")

            val lines = file.readLines()
            val keepFrom = (lines.size * 0.3).toInt()
            val linesToKeep = lines.drop(keepFrom)

            file.writeText("--- Log rotated at ${dateFormat.format(Date())} ---\n")
            file.appendText(linesToKeep.joinToString("\n"))
            file.appendText("\n")

            Log.i(TAG, "Log rotated, kept ${linesToKeep.size} lines")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log", e)
            try {
                file.writeText("--- Log cleared due to rotation error at ${dateFormat.format(Date())} ---\n")
            } catch (_: Exception) {}
        }
    }

    fun clear() {
        // Close before deleting, or the open handle keeps writing to an
        // unlinked inode and the UI shows an empty log that never fills.
        closeWriter()
        logFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun shutdown() {
        isRunning.set(false)
        writerThread?.interrupt()
        writeQueuedLogs()
        closeWriter()
    }
}
