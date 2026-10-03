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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.content.pm.PackageManager
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivitySettingsBinding
import com.tiredvpn.android.vpn.KillSwitch
import com.tiredvpn.android.vpn.SystemVpnMode
import com.tiredvpn.android.importer.ConfigCodec
import com.tiredvpn.android.importer.ImportPreview
import com.tiredvpn.android.util.SharedFiles
import com.tiredvpn.android.util.StoreWriteError
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.TiredVpnService
import com.tiredvpn.android.vpn.VpnState
import com.tiredvpn.android.vpn.VpnConfig

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** Pending "reconnect once the tunnel is really down" after a mode change. */
    internal var reconnectJob: Job? = null

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { restoreConfigs(it) } }

    companion object {

        /**
         * A config file is a few kilobytes. Anything past this is not one, and
         * reading it whole would be the picker's problem becoming ours. Same
         * ceiling as ImportActivity.
         */
        internal const val MAX_RESTORE_BYTES = 1 * 1024 * 1024

        // Full canonical strategy list. The stored value (first) is the EXACT
        // strategy ID accepted by the core (ForceStrategy by ID/prefix). Labels
        // are human-readable. "auto" means automatic selection (default).
        // Verified against tiredvpn-oss/internal/strategy/strategy.go.
        private val STRATEGIES = listOf(
            "auto" to "Auto (Best Available)",
            "reality" to "REALITY",
            "seqovl" to "Seqovl (sequence overlap)",
            "quic" to "QUIC",
            "quic_salamander" to "QUIC (Salamander)",
            "websocket_padded" to "WebSocket (Padded)",
            "http2_stego" to "HTTP/2 Steganography",
            "http_polling" to "HTTP Polling",
            // Canonical core strategy IDs (morph_ + profile name, with the literal
            // space). The JNI bridge now passes argv as a real String[], so these
            // IDs reach the core intact and ForceStrategy matches them exactly.
            "morph_Yandex Video" to "Traffic Morph (Yandex)",
            "morph_VK Video" to "Traffic Morph (VK)",
            "morph_Baidu Video" to "Traffic Morph (Baidu)",
            "morph_Aparat Video" to "Traffic Morph (Aparat)",
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.root)

        loadSettings()
        setupListeners()
    }

    /**
     * What the system says about always-on and lockdown for this app. Only the
     * service can ask (API 29+), so this is its last reading, labelled as such.
     */
    private fun blockWithoutVpnStatus(prefs: android.content.SharedPreferences): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return getString(R.string.block_without_vpn_legacy)
        val reading = SystemVpnMode.last(prefs) ?: return getString(R.string.block_without_vpn_unknown)
        fun onOff(v: Boolean) = getString(if (v) R.string.block_without_vpn_on else R.string.block_without_vpn_off)
        return getString(R.string.block_without_vpn_status, onOff(reading.alwaysOn), onOff(reading.lockdown))
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        updateBatteryOptimizationStatus()
    }

    /**
     * Store one per-server setting, and say so when the store refuses.
     *
     * Every writer on this screen used to drop the boolean saveServer returns
     * and then redraw the row from the config it had just built in memory, so a
     * refused write and a successful one were the same picture: the dialog
     * closed, the new value appeared, and the old one was back on the next
     * launch. The callers gate their redraw on this, so the value on screen is
     * the value in storage.
     *
     * A switch that was toggled stays where the finger left it - putting it
     * back from here would re-enter the listener that called us. The toast is
     * the report; the next [loadSettings] corrects the position.
     *
     * @return true when the write landed.
     */
    private fun persist(config: VpnConfig): Boolean = StoreWriteError.unless(
        ServerRepository.saveServer(this, config),
        this,
        R.string.store_save_failed,
    )

    private fun loadSettings() {
        val prefs = getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
        val config = ServerRepository.getActiveServer(this)

        // Load saved settings
        binding.connectOnLaunchSwitch.isChecked = prefs.getBoolean("connect_on_launch", false)
        binding.killSwitchSwitch.isChecked = prefs.getBoolean(KillSwitch.PREF, false)
        binding.blockWithoutVpnStatus.text = blockWithoutVpnStatus(prefs)

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

        // IPv6 inside the tunnel (dual-stack)
        binding.tunnelIpv6Switch.isChecked = config.tunnelIpv6 == "dual"

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
        binding.tunnelIpv6Switch.isEnabled = enabled
        binding.tunnelIpv6Row.alpha = alpha
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
                .putBoolean(KillSwitch.PREF, isChecked)
                .apply()
        }

        // Block connections without VPN - the system's always-on/lockdown screen
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
                persist(config.copy(quicSniFrag = isChecked))
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
                persist(config.copy(preferIpv6 = isChecked))
            }
        }

        // IPv4 fallback toggle
        binding.fallbackV4Switch.setOnCheckedChangeListener { _, isChecked ->
            val config = ServerRepository.getActiveServer(this)
            if (config != null && binding.fallbackV4Switch.isEnabled) {
                persist(config.copy(fallbackV4 = isChecked))
            }
        }

        // IPv6 inside the tunnel (dual-stack) toggle
        binding.tunnelIpv6Switch.setOnCheckedChangeListener { _, isChecked ->
            val config = ServerRepository.getActiveServer(this)
            if (config != null && binding.tunnelIpv6Switch.isEnabled) {
                persist(config.copy(tunnelIpv6 = if (isChecked) "dual" else "off"))
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

        // Auto Fallback toggle - persist to the active server so it survives
        // leaving and returning to settings (and is honored by the runtime command).
        binding.fallbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            val config = ServerRepository.getActiveServer(this)
            if (config != null && binding.fallbackSwitch.isEnabled && config.fallbackEnabled != isChecked) {
                persist(config.copy(fallbackEnabled = isChecked))
            }
        }

        // Debug logging toggle
        binding.debugLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            val config = ServerRepository.getActiveServer(this)
            if (config != null && binding.debugLoggingSwitch.isEnabled) {
                persist(config.copy(debugLogging = isChecked))
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
            // SharedFiles and not cacheDir: FileProvider only grants the two
            // subdirectories declared in file_paths.xml.
            val file = SharedFiles.file(this, "tiredvpn-backup.json")
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

    /**
     * Read the picked file off the main thread and refuse anything that is not
     * plausibly a config. The document comes from a picker, so it can be a
     * gigabyte of anything; reading it inline froze the screen for as long as
     * that took. The ceiling matches the one in ImportActivity.
     */
    private fun restoreConfigs(uri: Uri) {
        lifecycleScope.launch {
            when (val picked = withContext(Dispatchers.IO) { readPickedFile(uri) }) {
                is PickedFile.TooLarge ->
                    toast(getString(R.string.restore_too_large, MAX_RESTORE_BYTES / 1024 / 1024))
                is PickedFile.Unreadable ->
                    toast(getString(R.string.restore_unreadable, picked.reason))
                // The picked file goes through the same codec as every other
                // import, so a backup, a link list and a subscription blob all
                // restore identically.
                is PickedFile.Text -> ImportPreview.show(
                    this@SettingsActivity,
                    ConfigCodec.parse(picked.value),
                    fromExternalSource = false
                )
            }
        }
    }

    internal sealed interface PickedFile {
        data class Text(val value: String) : PickedFile
        data class Unreadable(val reason: String) : PickedFile
        data object TooLarge : PickedFile
    }

    /**
     * Read at most [MAX_RESTORE_BYTES] + 1 bytes, so an enormous document is
     * recognised as enormous without being held in memory first. Callers run
     * this off the main thread.
     */
    internal fun readPickedFile(uri: Uri): PickedFile = try {
        contentResolver.openInputStream(uri).use { stream ->
            if (stream == null) {
                PickedFile.Unreadable("no such document")
            } else {
                val buffer = ByteArray(MAX_RESTORE_BYTES + 1)
                var read = 0
                while (read < buffer.size) {
                    val n = stream.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read > MAX_RESTORE_BYTES) PickedFile.TooLarge
                else PickedFile.Text(String(buffer, 0, read, Charsets.UTF_8))
            }
        }
    } catch (e: Exception) {
        PickedFile.Unreadable(e.message ?: e.javaClass.simpleName)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

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
                if (persist(newConfig)) binding.protocolValue.text = names[which]
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
                if (persist(newConfig)) updateAdvancedSettingsDisplay(newConfig)
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
                if (persist(newConfig)) updateAdvancedSettingsDisplay(newConfig)
                dialog.dismiss()
            }
            .setPositiveButton("Custom") { dialog, _ ->
                val host = hostEditText.text?.toString()?.trim() ?: ""
                if (!InputValidation.isValidHost(host)) {
                    Toast.makeText(this, R.string.invalid_host, Toast.LENGTH_SHORT).show()
                } else {
                    val newConfig = config.copy(coverHost = host)
                    if (persist(newConfig)) updateAdvancedSettingsDisplay(newConfig)
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

    /** Same rule as before, from the shared validator (Patterns is deprecated). */
    private fun isValidIpAddress(value: String): Boolean =
        InputValidation.isIpv4Literal(value)

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
                if (persist(newConfig)) updateObfuscationDisplay(newConfig)
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

                // An ECHConfigList that isn't base64 cannot be decoded by
                // anything downstream, and the outer SNI has to be a real host.
                if (echConfig.isNotEmpty() && !InputValidation.isBase64(echConfig)) {
                    Toast.makeText(this, R.string.invalid_base64, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!InputValidation.isValidHost(publicName)) {
                    Toast.makeText(this, R.string.invalid_host, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newConfig = config.copy(
                    echEnabled = enabled,
                    echConfig = echConfig,
                    echPublicName = publicName
                )
                if (persist(newConfig)) updateObfuscationDisplay(newConfig)
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
                // Stored as typed, this field used to break the connection at
                // dial time instead of here. Empty stays legal - it means off.
                val v6 = InputValidation.parseV6Endpoint(hostEdit.text?.toString())
                if (v6 == null) {
                    Toast.makeText(this, R.string.invalid_v6_endpoint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newConfig = config.copy(serverAddressV6 = v6)
                if (persist(newConfig)) updateObfuscationDisplay(newConfig)
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
                // 0 (empty) = auto -> core default (1280). A custom value must be
                // in 1280..9000: below 1280 there is no headroom for tunnel
                // encapsulation (see #27); 9000 covers jumbo-frame paths. The same
                // value is sent both as -tun-mtu to the core and to
                // VpnService.Builder.setMtu(), so the two stay in sync.
                if (mtu == null || (mtu != 0 && mtu !in 1280..9000)) {
                    Toast.makeText(this, R.string.invalid_value, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newConfig = config.copy(mtu = mtu)
                if (persist(newConfig)) updateObfuscationDisplay(newConfig)
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
                if (persist(newConfig)) updateObfuscationDisplay(newConfig)
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

                // A mode change only means something for a live tunnel, and
                // Connecting counts: dropping the mode on it without a restart
                // would leave the core running the mode the user just left.
                val wasUp = TiredVpnService.state.value.let {
                    it is VpnState.Connected || it is VpnState.Connecting
                }

                if (wasUp) {
                    // Disconnect first
                    val stopIntent = Intent(this, TiredVpnService::class.java)
                    stopIntent.action = TiredVpnService.ACTION_DISCONNECT
                    startService(stopIntent)
                }

                // Save new mode
                val newConfig = config.copy(connectionMode = selectedMode)
                if (persist(newConfig)) loadSettings()

                // Reconnect when the service actually reports Disconnected. The
                // old 1.5 s timer was a guess: a CONNECT that arrives mid-teardown
                // is swallowed by the service's own "already connecting" guard,
                // and the timer fired regardless of whether the screen still
                // existed.
                if (wasUp) awaitDisconnectThenConnect()

                dialog.dismiss()
            }
            .show()
    }

    /**
     * Wait for the teardown the DISCONNECT above started, then connect again.
     *
     * Tied to the Activity's scope on purpose: if the user leaves this screen
     * the reconnect goes with it, which is the same thing the old timer did by
     * accident and this does on purpose.
     */
    private fun awaitDisconnectThenConnect() {
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            TiredVpnService.state.first { it is VpnState.Disconnected }
            val startIntent = Intent(this@SettingsActivity, TiredVpnService::class.java)
            startIntent.action = TiredVpnService.ACTION_CONNECT
            startService(startIntent)
        }
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
                // Out-of-range used to be dropped without a word, which reads
                // exactly like "saved" from the other side of the screen.
                val port = InputValidation.parsePort(
                    input.text.toString(),
                    min = InputValidation.MIN_USER_PORT
                )
                if (port == null) {
                    Toast.makeText(
                        this,
                        getString(
                            R.string.invalid_port,
                            InputValidation.MIN_USER_PORT,
                            InputValidation.MAX_PORT
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val newConfig = config.copy(proxyPort = port)
                if (persist(newConfig)) loadSettings()
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