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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            val strategyName = VpnConfig.STRATEGIES.find { it.first == config.strategy }?.second
                ?: getString(R.string.protocol_auto)
            binding.protocolValue.text = strategyName

            // Load advanced settings display
            updateAdvancedSettingsDisplay(config)

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
            binding.rttValue.text = "Disabled"
            binding.coverHostValue.text = "Not set"
            binding.debugLoggingSwitch.isChecked = false
            binding.fallbackSwitch.isChecked = true
        }
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
    }

    private fun updateAdvancedSettingsDisplay(config: VpnConfig) {
        // RTT Masking status
        binding.rttValue.text = if (config.rttMasking) {
            VpnConfig.RTT_PROFILES.find { it.first == config.rttProfile }?.second ?: config.rttProfile
        } else {
            "Disabled"
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

        // About
        binding.aboutRow.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun showProtocolDialog() {
        val config = ServerRepository.getActiveServer(this) ?: return
        
        val strategies = VpnConfig.STRATEGIES
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
        val profiles = VpnConfig.RTT_PROFILES
        val names = listOf("Disabled") + profiles.map { it.second }
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