package com.tiredvpn.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivityServerConfigBinding
import com.tiredvpn.android.importer.ConfigCodec
import com.tiredvpn.android.importer.ImportPreview
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

    /**
     * Free-text import. Deliberately not restricted to tired:// - the same box
     * takes a pasted JSON config or a base64 subscription blob, because the user
     * should not have to know which of those they were handed.
     */
    private fun showUrlInputDialog() {
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.import_paste_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(48, 24, 48, 24)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        inputLayout.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_paste_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.restore_import) { _, _ ->
                importText(input.text?.toString(), fromExternalSource = false)
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

        // Concatenate all clip items so we don't miss anything that landed in a
        // second item, and so a multi-item copy of several links imports as a set.
        val text = (0 until clipData.itemCount)
            .joinToString("\n") { clipData.getItemAt(it).coerceToText(this).toString() }

        if (text.isBlank()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }

        importText(text, fromExternalSource = false)
    }

    /**
     * The single import funnel for this screen. The whole text goes to the
     * codec: no pre-filtering to a single link, no format guess by the caller.
     */
    private fun importText(text: String?, fromExternalSource: Boolean) {
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.import_clipboard_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val parsed = ConfigCodec.parse(text)
        ImportPreview.show(this, parsed, fromExternalSource) { result ->
            if (result != null && result.added + result.updated > 0) finish()
        }
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