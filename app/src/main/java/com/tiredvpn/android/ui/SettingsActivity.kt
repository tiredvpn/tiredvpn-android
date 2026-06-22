package com.tiredvpn.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import java.io.File
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.content.pm.PackageManager
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivitySettingsBinding
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.TiredVpnService
import com.tiredvpn.android.vpn.VpnState
import com.tiredvpn.android.vpn.VpnConfig

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { restoreConfigs(it) } }

    companion object {
        // Full canonical strategy list. The stored value (first) is the EXACT
        // strategy ID accepted by the core (ForceStrategy by ID/prefix). Labels
        // are human-readable. "auto" means automatic selection (default).
        // Verified against tiredvpn-oss/internal/strategy/strategy.go.
        private val STRATEGIES = listOf(
            "auto" to "Auto (Best Available)",
            "reality" to "REALITY",
            "quic" to "QUIC",
            "quic_salamander" to "QUIC (Salamander)",
            "websocket_padded" to "WebSocket (Padded)",
            "http2_stego" to "HTTP/2 Steganography",
            "http_polling" to "HTTP Polling",
            // Space-free prefixes: core ForceStrategy() matches by prefix, and the
            // JNI bridge splits args on spaces, so the full IDs ("morph_Yandex Video")
            // would break. Each prefix uniquely matches its morph strategy.
            "morph_Yandex" to "Traffic Morph (Yandex)",
            "morph_VK" to "Traffic Morph (VK)",
            "morph_Baidu" to "Traffic Morph (Baidu)",
            "morph_Aparat" to "Traffic Morph (Aparat)",
            "ssh_camouflage" to "SSH Camouflage",
            "imap_camouflage" to "IMAP Camouflage",
            "antiprobe" to "Anti-Probe",
            "confusion_2" to "Protocol Confusion (SSH)",
            "confusion_0" to "Protocol Confusion (DNS)",
            "confusion_1" to "Protocol Confusion (HTTP)",
            "confusion_3" to "Protocol Confusion (TLS/SMTP)",
            "state_exhaustion" to "State Exhaustion",
            "geneva_russia" to "Geneva (Russia)",
            "geneva_china" to "Geneva (China)",
            "geneva_iran" to "Geneva (Iran)"
        )

        // Full RTT profile list (7), verified against internal/strategy/rtt.go.
        private val RTT_PROFILES = listOf(
            "moscow-yandex" to "Moscow - Yandex",
            "moscow-vk" to "Moscow - VK",
            "regional-russia" to "Regional Russia",
            "siberia" to "Siberia",
            "cdn" to "CDN",
            "beijing-baidu" to "Beijing - Baidu",
            "tehran-aparat" to "Tehran - Aparat"
        )

        // Traffic shaper presets. "" = off (default).
        private val SHAPER_PRESETS = listOf(
            "" to "Off",
            "youtube_streaming" to "YouTube Streaming",
            "chrome_browsing" to "Chrome Browsing",
            "random_per_session" to "Random (per session)"
        )

        // Port hop strategies accepted by the core: random / sequential / fibonacci.
        private val PORT_HOP_STRATEGIES = listOf(
            "random" to "Random",
            "sequential" to "Sequential",
            "fibonacci" to "Fibonacci"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.root)

        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        updateBatteryOptimizationStatus()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
        val config = ServerRepository.getActiveServer(this)

        // Load saved settings
        binding.connectOnLaunchSwitch.isChecked = prefs.getBoolean("connect_on_launch", false)
        binding.killSwitchSwitch.isChecked = prefs.getBoolean("kill_switch", false)

        // Version
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }
        binding.versionText.text = "TiredVPN for Android v$versionName"

        // Per-server settings
        if (config != null) {
            setPerServerSettingsEnabled(true)

            // Connection Mode (Android 10+ only)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                binding.connectionModeRow.visibility = View.VISIBLE
                binding.connectionModeDivider.visibility = View.VISIBLE
                val modeName = VpnConfig.CONNECTION_MODES.find { it.first == config.connectionMode }?.second
                    ?: getString(R.string.mode_vpn)
                binding.connectionModeValue.text = modeName

                // Show proxy port only in proxy mode
                if (config.connectionMode == "proxy") {
                    binding.proxyPortRow.visibility = View.VISIBLE
                    binding.proxyPortValue.text = config.proxyPort.toString()
                } else {
                    binding.proxyPortRow.visibility = View.GONE
                }
            } else {
                binding.connectionModeRow.visibility = View.GONE
                binding.proxyPortRow.visibility = View.GONE
                binding.connectionModeDivider.visibility = View.GONE
            }

            // Load protocol/strategy
            val strategyName = STRATEGIES.find { it.first == config.strategy }?.second
                ?: getString(R.string.protocol_auto)
            binding.protocolValue.text = strategyName

            // Load advanced settings display
            updateAdvancedSettingsDisplay(config)

            // Load obfuscation / network settings display
            updateObfuscationDisplay(config)

            // Load debug logging state
            binding.debugLoggingSwitch.isChecked = config.debugLogging

            // Load fallback state
            binding.fallbackSwitch.isChecked = config.fallbackEnabled
        } else {
            setPerServerSettingsEnabled(false)
            binding.connectionModeRow.visibility = View.GONE
            binding.proxyPortRow.visibility = View.GONE
            binding.connectionModeDivider.visibility = View.GONE
            binding.protocolValue.text = getString(R.string.protocol_auto)
            binding.rttValue.text = getString(R.string.value_disabled)
            binding.coverHostValue.text = getString(R.string.not_set)
            binding.portHoppingValue.text = getString(R.string.port_hop_disabled)
            binding.shaperValue.text = getString(R.string.traffic_shaper_off)
            binding.echValue.text = getString(R.string.value_disabled)
            binding.quicSniFragSwitch.isChecked = false
            binding.ipv6Value.text = getString(R.string.not_set)
            binding.preferIpv6Switch.isChecked = false
            binding.fallbackV4Switch.isChecked = true
            binding.mtuValue.text = getString(R.string.auto)
            binding.dnsValue.text = getString(R.string.auto)
            binding.debugLoggingSwitch.isChecked = false
            binding.fallbackSwitch.isChecked = true
        }
    }

    private fun updateObfuscationDisplay(config: VpnConfig) {
        // Port hopping
        binding.portHoppingValue.text = if (config.portHoppingEnabled) {
            "${config.portHopRangeStart}-${config.portHopRangeEnd}"
        } else {
            getString(R.string.port_hop_disabled)
        }

        // Traffic shaper
        binding.shaperValue.text = SHAPER_PRESETS.find { it.first == config.shaperPreset }?.second
            ?: getString(R.string.traffic_shaper_off)

        // ECH
        binding.echValue.text = if (config.echEnabled) {
            config.echPublicName.ifEmpty { getString(R.string.enabled) }
        } else {
            getString(R.string.value_disabled)
        }

        // QUIC SNI fragmentation
        binding.quicSniFragSwitch.isChecked = config.quicSniFrag

        // IPv6 endpoint
        binding.ipv6Value.text = config.serverAddressV6.ifEmpty { getString(R.string.not_set) }
        binding.preferIpv6Switch.isChecked = config.preferIpv6
        binding.fallbackV4Switch.isChecked = config.fallbackV4

        // Custom MTU
        binding.mtuValue.text = if (config.mtu > 0) config.mtu.toString() else getString(R.string.auto)

        // Custom DNS
        binding.dnsValue.text = config.customDns.ifEmpty { getString(R.string.auto) }
    }

    private fun setPerServerSettingsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.5f
        
        binding.protocolRow.isEnabled = enabled
        binding.protocolRow.alpha = alpha
        
        binding.rttRow.isEnabled = enabled
        binding.rttRow.alpha = alpha
        
        binding.coverHostRow.isEnabled = enabled
        binding.coverHostRow.alpha = alpha
        
        binding.debugLoggingSwitch.isEnabled = enabled
        binding.debugLoggingRow.alpha = alpha
        
        binding.fallbackSwitch.isEnabled = enabled
        binding.fallbackRow.alpha = alpha

        // Obfuscation
        binding.portHoppingRow.isEnabled = enabled
        binding.portHoppingRow.alpha = alpha
        binding.shaperRow.isEnabled = enabled
        binding.shaperRow.alpha = alpha
        binding.echRow.isEnabled = enabled
        binding.echRow.alpha = alpha
        binding.quicSniFragSwitch.isEnabled = enabled
        binding.quicSniFragRow.alpha = alpha

        // Network
        binding.ipv6Row.isEnabled = enabled
        binding.ipv6Row.alpha = alpha
        binding.preferIpv6Switch.isEnabled = enabled
        binding.preferIpv6Row.alpha = alpha
        binding.fallbackV4Switch.isEnabled = enabled
        binding.fallbackV4Row.alpha = alpha
        binding.mtuRow.isEnabled = enabled
        binding.mtuRow.alpha = alpha
        binding.dnsRow.isEnabled = enabled
        binding.dnsRow.alpha = alpha
    }

    private fun updateAdvancedSettingsDisplay(config: VpnConfig) {
        // RTT Masking status
        binding.rttValue.text = if (config.rttMasking) {
            RTT_PROFILES.find { it.first == config.rttProfile }?.second ?: config.rttProfile
        } else {
            getString(R.string.value_disabled)
        }

        // Cover host
        binding.coverHostValue.text = config.coverHost.ifEmpty { "Not set" }
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }

        // Connect on launch toggle
        binding.connectOnLaunchSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
                .edit()
                .putBoolean("connect_on_launch", isChecked)
                .apply()
        }

        // Kill switch toggle
        binding.killSwitchSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
                .edit()
                .putBoolean("kill_switch", isChecked)
                .apply()
        }

        // Always-on VPN - opens system VPN settings
        binding.alwaysOnVpnRow.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (e: Exception) {
                // Fallback to general wireless settings if VPN settings not available
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }

        // Connection Mode selector (Android 10+ only)
        binding.connectionModeRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showConnectionModeDialog()
        }

        // Proxy Port selector
        binding.proxyPortRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showProxyPortDialog()
        }

        // Protocol selector
        binding.protocolRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showProtocolDialog()
        }

        // RTT Masking settings
        binding.rttRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showRttDialog()
        }

        // Cover host settings
        binding.coverHostRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showCoverHostDialog()
        }

        // Port hopping settings
        binding.portHoppingRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showPortHoppingDialog()
        }

        // Traffic shaper settings
        binding.shaperRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showShaperDialog()
        }

        // ECH settings
        binding.echRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showEchDialog()
        }

        // QUIC SNI fragmentation toggle
        binding.quicSniFragSwitch.setOnCheckedChangeListener { _, isChecked ->
            val config = ServerRepository.getActiveServer(this)
            if (config != null && binding.quicSniFragSwitch.isEnabled) {
                ServerRepository.saveServer(this, config.copy(quicSniFrag = isChecked))
            }
        }

        // IPv6 endpoint settings
        binding.ipv6Row.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showIpv6Dialog()
        }

        // Prefer IPv6 toggle
        binding.preferIpv6Switch.setOnCheckedChangeListener { _, isChecked ->
            val config = ServerRepository.getActiveServer(this)
            if (config != null && binding.preferIpv6Switch.isEnabled) {
                ServerRepository.saveServer(this, config.copy(preferIpv6 = isChecked))
            }
        }

        // IPv4 fallback toggle
        binding.fallbackV4Switch.setOnCheckedChangeListener { _, isChecked ->
            val config = ServerRepository.getActiveServer(this)
            if (config != null && binding.fallbackV4Switch.isEnabled) {
                ServerRepository.saveServer(this, config.copy(fallbackV4 = isChecked))
            }
        }

        // Custom MTU settings
        binding.mtuRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showMtuDialog()
        }

        // Custom DNS settings
        binding.dnsRow.setOnClickListener {
            if (ServerRepository.getActiveServer(this) != null) showDnsDialog()
        }

        // Split tunneling
        binding.splitTunnelingRow.setOnClickListener {
            startActivity(Intent(this, SplitTunnelingActivity::class.java))
        }

        // Battery optimization
        binding.batteryOptimizationRow.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // Debug logging toggle
        binding.debugLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            val config = ServerRepository.getActiveServer(this)
            if (config != null && binding.debugLoggingSwitch.isEnabled) {
                ServerRepository.saveServer(this, config.copy(debugLogging = isChecked))
            }
        }

        // View logs
        binding.viewLogsRow.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        // Backup / export configs to a file and share
        binding.backupConfigsRow.setOnClickListener {
            backupConfigs()
        }

        // Restore configs from a backup file
        binding.restoreConfigsRow.setOnClickListener {
            try {
                restoreLauncher.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
                )
            } catch (e: Exception) {
                Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
            }
        }

        // About
        binding.aboutRow.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun backupConfigs() {
        val servers = ServerRepository.getServers(this)
        if (servers.isEmpty()) {
            Toast.makeText(this, R.string.backup_no_servers, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_configs)
            .setMessage(getString(R.string.backup_warning, servers.size))
            .setPositiveButton(R.string.backup_share) { _, _ -> exportAndShare(servers) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportAndShare(servers: List<VpnConfig>) {
        try {
            val json = JSONArray().apply { servers.forEach { put(it.toJson()) } }
            val file = File(cacheDir, "tiredvpn-backup.json")
            file.writeText(json.toString(2))

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "TiredVPN backup (${servers.size} servers)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.backup_share)))
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreConfigs(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        val configs = parseBackup(text)
        if (configs.isEmpty()) {
            Toast.makeText(this, R.string.restore_invalid, Toast.LENGTH_LONG).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_configs)
            .setMessage(getString(R.string.restore_found, configs.size))
            .setPositiveButton(R.string.restore_import) { _, _ ->
                configs.forEach { ServerRepository.saveServer(this, it) }
                Toast.makeText(
                    this,
                    getString(R.string.restore_done, configs.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Parse a backup payload into valid configs. Accepts a JSON array of servers
     * (the backup format), a single JSON object, or text containing tired:// links.
     */
    private fun parseBackup(text: String): List<VpnConfig> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // JSON array (backup file) or single object.
        try {
            when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    return (0 until arr.length())
                        .map { VpnConfig.fromJson(arr.getJSONObject(it)) }
                        .filter { it.isValid }
                }
                trimmed.startsWith("{") -> {
                    val config = VpnConfig.fromJson(org.json.JSONObject(trimmed))
                    return if (config.isValid) listOf(config) else emptyList()
                }
            }
        } catch (e: Exception) {
            // fall through to link parsing
        }

        // Fallback: one tired:// link per line.
        return trimmed.lineSequence()
            .mapNotNull { VpnConfig.fromUrl(it) }
            .filter { it.isValid }
            .toList()
    }

    private fun showProtocolDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return
        
        val strategies = STRATEGIES
        val names = strategies.map { it.second }.toTypedArray()
        val values = strategies.map { it.first }

        val currentIndex = values.indexOf(config.strategy).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_protocol)
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val selectedStrategy = values[which]
                val newConfig = config.copy(strategy = selectedStrategy)
                ServerRepository.saveServer(this, newConfig)
                binding.protocolValue.text = names[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun showRttDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return
        val profiles = RTT_PROFILES
        val names = listOf(getString(R.string.value_disabled)) + profiles.map { it.second }
        val values = listOf("") + profiles.map { it.first }

        val currentIndex = if (!config.rttMasking) {
            0
        } else {
            values.indexOf(config.rttProfile).coerceAtLeast(0)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("RTT Masking")
            .setSingleChoiceItems(names.toTypedArray(), currentIndex) { dialog, which ->
                val newConfig = if (which == 0) {
                    config.copy(rttMasking = false)
                } else {
                    config.copy(rttMasking = true, rttProfile = values[which])
                }
                ServerRepository.saveServer(this, newConfig)
                updateAdvancedSettingsDisplay(newConfig)
                dialog.dismiss()
            }
            .show()
    }

    private fun showCoverHostDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
        }

        val hostInput = TextInputLayout(this).apply {
            hint = "Cover Host"
            helperText = "Host to mimic in traffic patterns"
        }
        val hostEditText = TextInputEditText(this).apply {
            setText(config.coverHost)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        hostInput.addView(hostEditText)
        layout.addView(hostInput)

        // Preset hosts
        val presets = arrayOf(
            "api.googleapis.com",
            "www.google.com",
            "yandex.ru",
            "vk.com",
            "ok.ru"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Cover Host")
            .setView(layout)
            .setItems(presets) { dialog, which ->
                val newConfig = config.copy(coverHost = presets[which])
                ServerRepository.saveServer(this, newConfig)
                updateAdvancedSettingsDisplay(newConfig)
                dialog.dismiss()
            }
            .setPositiveButton("Custom") { dialog, _ ->
                val host = hostEditText.text?.toString()?.trim() ?: ""
                if (host.isNotEmpty()) {
                    val newConfig = config.copy(coverHost = host)
                    ServerRepository.saveServer(this, newConfig)
                    updateAdvancedSettingsDisplay(newConfig)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Helpers for building dialog form fields (mirrors showCoverHostDialog style) ---

    private fun dialogContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(64, 32, 64, 0)
    }

    private fun textField(
        hintText: String,
        value: String,
        helper: String? = null,
        inputTypeFlags: Int = InputType.TYPE_CLASS_TEXT
    ): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(this).apply {
            hint = hintText
            if (helper != null) helperText = helper
        }
        val edit = TextInputEditText(this).apply {
            setText(value)
            inputType = inputTypeFlags
        }
        layout.addView(edit)
        return layout to edit
    }

    private fun isValidIpAddress(value: String): Boolean =
        android.util.Patterns.IP_ADDRESS.matcher(value).matches()

    private fun showPortHoppingDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return

        val layout = dialogContainer()

        val enabledSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.port_hopping)
            isChecked = config.portHoppingEnabled
        }
        layout.addView(enabledSwitch)

        val (startLayout, startEdit) = textField(
            getString(R.string.port_hop_range_start),
            config.portHopRangeStart.toString(),
            inputTypeFlags = InputType.TYPE_CLASS_NUMBER
        )
        val (endLayout, endEdit) = textField(
            getString(R.string.port_hop_range_end),
            config.portHopRangeEnd.toString(),
            inputTypeFlags = InputType.TYPE_CLASS_NUMBER
        )
        val (intervalLayout, intervalEdit) = textField(
            getString(R.string.port_hop_interval_hint),
            (config.portHopIntervalMs / 1000L).toString(),
            inputTypeFlags = InputType.TYPE_CLASS_NUMBER
        )
        val (seedLayout, seedEdit) = textField(
            getString(R.string.port_hop_seed),
            config.portHopSeed ?: "",
            helper = getString(R.string.port_hop_seed_hint)
        )

        // Strategy spinner-like: use a read-only field that opens a sub-dialog
        var selectedHopStrategy = config.portHopStrategy
        val (strategyLayout, strategyEdit) = textField(
            getString(R.string.port_hop_strategy),
            PORT_HOP_STRATEGIES.find { it.first == selectedHopStrategy }?.second ?: selectedHopStrategy
        )
        strategyEdit.isFocusable = false
        strategyEdit.isClickable = true
        strategyEdit.setOnClickListener {
            val names = PORT_HOP_STRATEGIES.map { it.second }.toTypedArray()
            val values = PORT_HOP_STRATEGIES.map { it.first }
            val idx = values.indexOf(selectedHopStrategy).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.port_hop_strategy)
                .setSingleChoiceItems(names, idx) { d, which ->
                    selectedHopStrategy = values[which]
                    strategyEdit.setText(names[which])
                    d.dismiss()
                }
                .show()
        }

        listOf(startLayout, endLayout, intervalLayout, strategyLayout, seedLayout).forEach { layout.addView(it) }

        fun setDetailVisibility(visible: Boolean) {
            val vis = if (visible) View.VISIBLE else View.GONE
            listOf(startLayout, endLayout, intervalLayout, strategyLayout, seedLayout).forEach { it.visibility = vis }
        }
        setDetailVisibility(config.portHoppingEnabled)
        enabledSwitch.setOnCheckedChangeListener { _, checked -> setDetailVisibility(checked) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.port_hopping)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val enabled = enabledSwitch.isChecked
                if (!enabled) {
                    val newConfig = config.copy(portHoppingEnabled = false)
                    ServerRepository.saveServer(this, newConfig)
                    updateObfuscationDisplay(newConfig)
                    return@setPositiveButton
                }
                val start = startEdit.text?.toString()?.trim()?.toIntOrNull()
                val end = endEdit.text?.toString()?.trim()?.toIntOrNull()
                val intervalSec = intervalEdit.text?.toString()?.trim()?.toLongOrNull()
                if (start == null || end == null || start !in 1024..65535 || end !in 1024..65535 || start >= end) {
                    Toast.makeText(this, R.string.invalid_port_range, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val intervalMs = ((intervalSec ?: 60L).coerceAtLeast(1L)) * 1000L
                val seed = seedEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val newConfig = config.copy(
                    portHoppingEnabled = true,
                    portHopRangeStart = start,
                    portHopRangeEnd = end,
                    portHopIntervalMs = intervalMs,
                    portHopStrategy = selectedHopStrategy,
                    portHopSeed = seed
                )
                ServerRepository.saveServer(this, newConfig)
                updateObfuscationDisplay(newConfig)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showShaperDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return

        val layout = dialogContainer()
        val (seedLayout, seedEdit) = textField(
            getString(R.string.shaper_seed),
            if (config.shaperSeed != 0L) config.shaperSeed.toString() else "",
            helper = getString(R.string.shaper_seed_hint),
            inputTypeFlags = InputType.TYPE_CLASS_NUMBER
        )
        layout.addView(seedLayout)

        val names = SHAPER_PRESETS.map { it.second }.toTypedArray()
        val values = SHAPER_PRESETS.map { it.first }
        var selectedPreset = config.shaperPreset
        val checkedIndex = values.indexOf(selectedPreset).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.traffic_shaper)
            .setSingleChoiceItems(names, checkedIndex) { _, which ->
                selectedPreset = values[which]
            }
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val seed = seedEdit.text?.toString()?.trim()?.toLongOrNull() ?: 0L
                val newConfig = config.copy(shaperPreset = selectedPreset, shaperSeed = seed)
                ServerRepository.saveServer(this, newConfig)
                updateObfuscationDisplay(newConfig)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEchDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return

        val layout = dialogContainer()
        val enabledSwitch = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = getString(R.string.ech)
            isChecked = config.echEnabled
        }
        layout.addView(enabledSwitch)

        val (configLayout, configEdit) = textField(
            getString(R.string.ech_config),
            config.echConfig,
            helper = getString(R.string.ech_config_hint)
        )
        val (nameLayout, nameEdit) = textField(
            getString(R.string.ech_public_name),
            config.echPublicName,
            helper = getString(R.string.ech_public_name_hint)
        )
        layout.addView(configLayout)
        layout.addView(nameLayout)

        fun setDetailVisibility(visible: Boolean) {
            val vis = if (visible) View.VISIBLE else View.GONE
            configLayout.visibility = vis
            nameLayout.visibility = vis
        }
        setDetailVisibility(config.echEnabled)
        enabledSwitch.setOnCheckedChangeListener { _, checked -> setDetailVisibility(checked) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ech)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val enabled = enabledSwitch.isChecked
                val echConfig = configEdit.text?.toString()?.trim() ?: ""
                val publicName = nameEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "cloudflare-ech.com"
                val newConfig = config.copy(
                    echEnabled = enabled,
                    echConfig = echConfig,
                    echPublicName = publicName
                )
                ServerRepository.saveServer(this, newConfig)
                updateObfuscationDisplay(newConfig)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showIpv6Dialog() {
        val config = ServerRepository.getActiveServer(this) ?: return

        val layout = dialogContainer()
        val (hostLayout, hostEdit) = textField(
            getString(R.string.server_address_v6),
            config.serverAddressV6,
            helper = getString(R.string.server_address_v6_hint)
        )
        layout.addView(hostLayout)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.server_address_v6)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val v6 = hostEdit.text?.toString()?.trim() ?: ""
                val newConfig = config.copy(serverAddressV6 = v6)
                ServerRepository.saveServer(this, newConfig)
                updateObfuscationDisplay(newConfig)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMtuDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (config.mtu > 0) config.mtu.toString() else "")
            hint = getString(R.string.custom_mtu_hint)
            setPadding(64, 32, 64, 16)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_mtu)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val raw = input.text.toString().trim()
                val mtu = if (raw.isEmpty()) 0 else raw.toIntOrNull()
                if (mtu == null || (mtu != 0 && mtu !in 576..1500)) {
                    Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newConfig = config.copy(mtu = mtu)
                ServerRepository.saveServer(this, newConfig)
                updateObfuscationDisplay(newConfig)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDnsDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return

        val layout = dialogContainer()
        val (dnsLayout, dnsEdit) = textField(
            getString(R.string.custom_dns),
            config.customDns,
            helper = getString(R.string.custom_dns_hint)
        )
        layout.addView(dnsLayout)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_dns)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val dns = dnsEdit.text?.toString()?.trim() ?: ""
                if (dns.isNotEmpty() && !isValidIpAddress(dns)) {
                    Toast.makeText(this, R.string.invalid_dns, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newConfig = config.copy(customDns = dns)
                ServerRepository.saveServer(this, newConfig)
                updateObfuscationDisplay(newConfig)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConnectionModeDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return

        val modes = VpnConfig.CONNECTION_MODES
        val names = modes.map { it.second }.toTypedArray()
        val values = modes.map { it.first }

        val currentIndex = values.indexOf(config.connectionMode).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connection_mode)
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                val selectedMode = values[which]

                // If mode hasn't changed, do nothing
                if (selectedMode == config.connectionMode) {
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }

                // Check if VPN is currently connected
                val wasConnected = TiredVpnService.state.value is VpnState.Connected

                if (wasConnected) {
                    // Disconnect first
                    val stopIntent = Intent(this, TiredVpnService::class.java)
                    stopIntent.action = TiredVpnService.ACTION_DISCONNECT
                    startService(stopIntent)
                }

                // Save new mode
                val newConfig = config.copy(connectionMode = selectedMode)
                ServerRepository.saveServer(this, newConfig)
                loadSettings()

                if (wasConnected) {
                    // Reconnect in new mode after a short delay
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val startIntent = Intent(this, TiredVpnService::class.java)
                        startIntent.action = TiredVpnService.ACTION_CONNECT
                        startService(startIntent)
                    }, 1500)
                }

                dialog.dismiss()
            }
            .show()
    }

    private fun showProxyPortDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.proxyPort.toString())
            setPadding(64, 32, 64, 16)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.proxy_port)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val port = input.text.toString().toIntOrNull() ?: 8080
                if (port in 1024..65535) {
                    val newConfig = config.copy(proxyPort = port)
                    ServerRepository.saveServer(this, newConfig)
                    loadSettings()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    /**
     * Check and update battery optimization status.
     * If battery optimization is enabled for this app, Android may kill
     * the VPN process in the background.
     */
    private fun updateBatteryOptimizationStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)

        if (isIgnoringBatteryOptimizations) {
            binding.batteryOptimizationStatus.text = getString(R.string.battery_optimization_disabled)
            binding.batteryOptimizationStatus.setTextColor(getColor(R.color.status_connected))
        } else {
            binding.batteryOptimizationStatus.text = getString(R.string.battery_optimization_enabled)
            binding.batteryOptimizationStatus.setTextColor(getColor(R.color.status_error))
        }
    }

    /**
     * Request battery optimization exemption.
     * This is critical for VPN reliability - without it, Android may kill
     * the VPN process when the device goes to sleep or battery saver is active.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // Already exempted, show info
            Toast.makeText(
                this,
                "Battery optimization is already disabled for TiredVPN",
                Toast.LENGTH_SHORT
            ).show()

            // Open app settings anyway so user can verify
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback to battery optimization settings
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            return
        }

        // Show explanation dialog first
        MaterialAlertDialogBuilder(this)
            .setTitle("Battery Optimization")
            .setMessage(
                "For reliable VPN connections, TiredVPN needs to run without battery restrictions.\n\n" +
                "Without this exemption, Android may kill the VPN when your device sleeps, " +
                "causing disconnections.\n\n" +
                "This does NOT significantly affect battery life."
            )
            .setPositiveButton("Disable Optimization") { _, _ ->
                // Try direct request first (requires permission in manifest)
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to battery optimization settings list
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        // Last resort - open app details
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }
}