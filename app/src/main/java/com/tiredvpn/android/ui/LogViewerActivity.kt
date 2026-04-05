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
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivityLogViewerBinding
import com.tiredvpn.android.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogViewerActivity : BaseActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private var currentLogs: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.root)

        setupListeners()
        loadLogs()
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
            val logFile = File(filesDir, "tiredvpn.log")
            if (logFile.exists() && logFile.length() > 0) {
                // Read last 1000 lines to avoid OOM
                val lines = logFile.readLines()
                val lastLines = if (lines.size > 1000) {
                    lines.takeLast(1000)
                } else {
                    lines
                }
                lastLines.joinToString("\n")
            } else {
                ""
            }
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    private fun copyLogsToClipboard() {
        if (currentLogs.isEmpty()) {
            Toast.makeText(this, R.string.no_logs, Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TiredVPN Logs", currentLogs)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
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
        if (currentLogs.isEmpty()) {
            Toast.makeText(this, R.string.no_logs, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val shareFile = File(cacheDir, "tiredvpn_logs.txt")
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
