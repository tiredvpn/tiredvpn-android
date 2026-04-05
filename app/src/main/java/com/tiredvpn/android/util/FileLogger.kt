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
        logFile = File(context.filesDir, LOG_FILE_NAME)
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

    private fun writeQueuedLogs() {
        val file = logFile ?: return
        if (logQueue.isEmpty()) return

        try {
            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                rotateLog(file)
            }

            FileOutputStream(file, true).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    var line: String?
                    var count = 0
                    while (logQueue.poll().also { line = it } != null && count < 100) {
                        writer.appendLine(line)
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write logs", e)
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
    }
}
