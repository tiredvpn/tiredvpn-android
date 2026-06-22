package com.tiredvpn.android.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivityServerConfigBinding
import com.tiredvpn.android.util.TvUtils
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.VpnConfig

class ServerConfigActivity : BaseActivity() {

    private lateinit var binding: ActivityServerConfigBinding
    private var editingServerId: String? = null

    // QR Scanner disabled for 16KB page support on Pixel 9
    // private val qrScannerLauncher = registerForActivityResult(
    //     ActivityResultContracts.StartActivityForResult()
    // ) { result ->
    //     if (result.resultCode == Activity.RESULT_OK) {
    //         result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_RESULT)?.let { url ->
    //             parseAndApplyUrl(url)
    //         }
    //     }
    // }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.root)

        editingServerId = intent.getStringExtra("SERVER_ID")

        loadConfig()
        setupListeners()
        setupTvMode()

        // Check if launched with tired:// intent
        handleIntent()
    }

    private fun setupTvMode() {
        if (TvUtils.isTv(this)) {
            // Hide QR scanner on TV (no camera)
            binding.scanQrButton.visibility = View.GONE
            binding.qrDivider.visibility = View.GONE
            // Set initial focus to first import option
            binding.importClipboardButton.requestFocus()
        }
    }

    private fun handleIntent() {
        intent?.data?.let { uri ->
            if (uri.scheme == "tired") {
                parseAndApplyUrl(uri.toString(), fromExternalSource = true)
            }
        }
    }

    private fun loadConfig() {
        if (editingServerId != null) {
            val config = ServerRepository.getServer(this, editingServerId!!)
            if (config != null) {
                binding.serverNameInput.setText(config.name)
                binding.serverAddressInput.setText(config.serverAddress)
                binding.serverPortInput.setText(config.serverPort.toString())
                binding.secretInput.setText(config.secret)
            } else {
                Toast.makeText(this, "Server not found", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            // New server defaults
            binding.serverPortInput.setText("993")
        }
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.saveButton.setOnClickListener {
            saveConfig()
        }

        binding.importClipboardButton.setOnClickListener {
            importFromClipboard()
        }

        // QR Scanner disabled for 16KB page support on Pixel 9
        binding.scanQrButton.setOnClickListener {
            Toast.makeText(this, "QR Scanner temporarily disabled", Toast.LENGTH_SHORT).show()
            // qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
        }

        binding.enterUrlButton.setOnClickListener {
            showUrlInputDialog()
        }
    }

    private fun showUrlInputDialog() {
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.enter_url_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(48, 24, 48, 24)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        inputLayout.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_url_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = input.text?.toString()?.trim() ?: ""
                if (url.isNotEmpty()) {
                    if (url.startsWith("tired://")) {
                        parseAndApplyUrl(url, fromExternalSource = false)
                    } else {
                        Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        // Request focus and show keyboard
        input.requestFocus()
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        if (clipData == null || clipData.itemCount == 0) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }

        // Concatenate all clip items so we don't miss the link if it lands in a second item.
        val text = (0 until clipData.itemCount)
            .joinToString("\n") { clipData.getItemAt(it).coerceToText(this).toString() }
            .trim()

        if (text.isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }

        // Try tired:// link (tolerant: handles whitespace and links embedded in text).
        val link = VpnConfig.extractTiredUrl(text)
        if (link != null) {
            parseAndApplyUrl(link, fromExternalSource = false)
            return
        }

        // Fall back to JSON config exported via backup.
        val jsonConfig = tryParseJsonConfig(text)
        if (jsonConfig != null) {
            confirmAndSave(jsonConfig, fromExternalSource = false)
            return
        }

        Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
    }

    private fun tryParseJsonConfig(text: String): VpnConfig? {
        return try {
            val trimmed = text.trim()
            when {
                trimmed.startsWith("{") -> VpnConfig.fromJson(org.json.JSONObject(trimmed))
                trimmed.startsWith("[") -> {
                    val arr = org.json.JSONArray(trimmed)
                    if (arr.length() > 0) VpnConfig.fromJson(arr.getJSONObject(0)) else null
                }
                else -> null
            }?.takeIf { it.isValid }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse tired:// URL format:
     * tired://SERVER:PORT?secret=SECRET&strategy=auto&quic=true&quicPort=443&cover=host&rtt=false&rttProfile=moscow-yandex&fallback=true
     *
     * Minimal format:
     * tired://SERVER:PORT?secret=SECRET
     */
    private fun parseAndApplyUrl(url: String, fromExternalSource: Boolean = false): Boolean {
        val config = VpnConfig.fromUrl(url)
        if (config == null) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            return false
        }
        confirmAndSave(config, fromExternalSource)
        return true
    }

    private fun confirmAndSave(config: VpnConfig, fromExternalSource: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Import Configuration")
            .setMessage(buildString {
                if (fromExternalSource) {
                    appendLine("⚠️ This configuration was opened from an external link (browser or another app). Make sure you trust the source.")
                    appendLine()
                }
                appendLine("Server: ${config.serverAddress}:${config.serverPort}")
                appendLine("Strategy: ${config.strategy}")
                appendLine("QUIC: ${if (config.enableQuic) "Enabled (port ${config.quicPort})" else "Disabled"}")
                appendLine("Cover Host: ${config.coverHost}")
                appendLine("RTT Masking: ${if (config.rttMasking) config.rttProfile else "Disabled"}")
                appendLine("Fallback: ${if (config.fallbackEnabled) "Enabled" else "Disabled"}")
            })
            .setPositiveButton("Import") { _, _ ->
                ServerRepository.saveServer(this, config)
                Toast.makeText(this, R.string.config_imported, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveConfig() {
        val name = binding.serverNameInput.text?.toString()?.trim() ?: ""
        val serverAddress = binding.serverAddressInput.text?.toString()?.trim() ?: ""
        val serverPort = binding.serverPortInput.text?.toString()?.toIntOrNull() ?: 993
        val secret = binding.secretInput.text?.toString() ?: ""

        // Validate
        if (serverAddress.isEmpty()) {
            binding.serverAddressInput.error = getString(R.string.required)
            return
        }

        if (secret.isEmpty()) {
            binding.secretInput.error = getString(R.string.required)
            return
        }
        
        val finalName = if (name.isEmpty()) serverAddress else name

        // Load existing config to preserve other settings if editing
        val baseConfig = if (editingServerId != null) {
            ServerRepository.getServer(this, editingServerId!!) ?: VpnConfig(
                serverAddress = serverAddress, serverPort = serverPort, secret = secret
            )
        } else {
             VpnConfig(
                serverAddress = serverAddress, serverPort = serverPort, secret = secret
            )
        }

        // Save with updated server info
        val config = baseConfig.copy(
            name = finalName,
            serverAddress = serverAddress,
            serverPort = serverPort,
            secret = secret
        )
        ServerRepository.saveServer(this, config)

        Toast.makeText(this, "Server saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}