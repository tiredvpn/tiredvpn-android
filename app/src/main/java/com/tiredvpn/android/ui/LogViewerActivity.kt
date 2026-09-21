package com.tiredvpn.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivityLogViewerBinding
import com.tiredvpn.android.util.FileLogger
import com.tiredvpn.android.util.SharedFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class LogViewerActivity : BaseActivity() {

    companion object {
        /** How much of the log the screen shows, and shares. */
        internal const val MAX_LINES = 1000

        /**
         * Bytes read from the end of the file to find those lines. A log line
         * here runs well under 200 bytes, so this is a generous ceiling - and a
         * ceiling is the point: the file grows without bound and
         * File.readLines() used to pull all of it into memory to keep the tail.
         */
        internal const val TAIL_BYTES = 512 * 1024L

        /**
         * The last [maxLines] lines of [file], read from the end.
         *
         * Cutting by bytes can land mid-line, so the first (partial) line of
         * the window is dropped whenever the window didn't start at the top of
         * the file.
         */
        internal fun tailOf(file: File, maxLines: Int, windowBytes: Long = TAIL_BYTES): String {
            if (!file.exists() || file.length() == 0L) return ""

            val length = file.length()
            val from = (length - windowBytes).coerceAtLeast(0)
            val window = ByteArray((length - from).toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                raf.readFully(window)
            }

            var lines = String(window, Charsets.UTF_8).split("\n")
            if (from > 0 && lines.isNotEmpty()) lines = lines.drop(1)
            return lines.takeLast(maxLines).joinToString("\n")
        }
    }

    private lateinit var binding: ActivityLogViewerBinding
    private var currentLogs: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.root)

        setupListeners()
        // No load here: onResume runs right after onCreate and does it, and
        // doing both read the file twice on every open.
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.copyButton.setOnClickListener {
            copyLogsToClipboard()
        }

        binding.clearButton.setOnClickListener {
            clearLogs()
        }

        binding.shareButton.setOnClickListener {
            shareLogs()
        }
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) {
                readLogFile()
            }
            currentLogs = logs
            binding.logContent.text = logs.ifEmpty { getString(R.string.no_logs) }

            // Scroll to bottom
            binding.scrollView.post {
                binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun readLogFile(): String {
        return try {
            tailOf(File(filesDir, "tiredvpn.log"), MAX_LINES)
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    /**
     * Say what is in the log before it leaves the screen.
     *
     * The log names every server in the pool and carries the core's output.
     * The clipboard is readable by any app in the background below Android 10,
     * and a share target is whatever the user taps - neither is a place to send
     * that by reflex.
     */
    private fun confirmThenSend(messageRes: Int, actionRes: Int, action: () -> Unit) {
        if (currentLogs.isEmpty()) {
            Toast.makeText(this, R.string.no_logs, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_privacy_title)
            .setMessage(messageRes)
            .setPositiveButton(actionRes) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copyLogsToClipboard() {
        confirmThenSend(R.string.logs_copy_privacy_message, R.string.copy_logs) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("TiredVPN Logs", currentLogs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearLogs() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                FileLogger.clear()
            }
            currentLogs = ""
            binding.logContent.text = getString(R.string.no_logs)
            Toast.makeText(this@LogViewerActivity, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLogs() {
        confirmThenSend(R.string.logs_share_privacy_message, R.string.share_logs) {
            doShareLogs()
        }
    }

    private fun doShareLogs() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                // SharedFiles and not cacheDir: FileProvider only grants the
                // two subdirectories declared in file_paths.xml, and the
                // fallback below is silent — a file written outside them turns
                // "share the log file" into "paste the log as a message" with
                // nothing said about it.
                val shareFile = SharedFiles.file(this@LogViewerActivity, "tiredvpn_logs.txt")
                shareFile.writeText(currentLogs)
                shareFile
            }

            try {
                val uri = FileProvider.getUriForFile(
                    this@LogViewerActivity,
                    "${packageName}.fileprovider",
                    file
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "TiredVPN Logs")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_logs)))
            } catch (e: Exception) {
                // Fallback to text share
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentLogs)
                    putExtra(Intent.EXTRA_SUBJECT, "TiredVPN Logs")
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_logs)))
            }
        }
    }
}
