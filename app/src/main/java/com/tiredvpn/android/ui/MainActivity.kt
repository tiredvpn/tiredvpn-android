package com.tiredvpn.android.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivityMainBinding
import com.tiredvpn.android.update.ApkInstaller
import com.tiredvpn.android.update.UpdateConfig
import com.tiredvpn.android.update.UpdateManager
import com.tiredvpn.android.util.ErrorDialogHelper
import com.tiredvpn.android.util.FileLogger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tiredvpn.android.vpn.TiredVpnService
import com.tiredvpn.android.vpn.VpnConfig
import com.tiredvpn.android.vpn.VpnState
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.util.CountryDetector
import com.tiredvpn.android.util.TvUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : BaseActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var updateManager: UpdateManager
    private var ipFetchJob: Job? = null
    private var countryFetchJob: Job? = null
    private var pendingForceUpdate: UpdateConfig? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Check if exemption was granted
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Battery optimization disabled - VPN will stay connected", Toast.LENGTH_SHORT).show()
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Check if permission was granted and retry update
        if (updateManager.canInstall()) {
            pendingForceUpdate?.let { config ->
                lifecycleScope.launch {
                    updateManager.downloadAndInstall(config)
                }
            }
        } else if (pendingForceUpdate?.forceUpdate == true) {
            // Force update required but permission denied - close app
            Toast.makeText(this, "Update required to continue", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.rootLayout)

        updateManager = UpdateManager(this)

        // Check if first launch - show welcome screen
        val isFirstRun = isFirstLaunch()
        if (isFirstRun) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        requestNotificationPermission()
        requestBatteryOptimizationExemption()
        setupListeners()
        observeVpnState()
        updateServerInfo()
        setupTvMode()

        // Check if launched from update notification
        if (intent?.getBooleanExtra("install_update", false) == true) {
            installDownloadedUpdate()
        } else {
            checkForUpdates()
        }

        if (!isFirstRun) {
            checkConnectOnLaunch()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // Request exemption - this shows a system dialog
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    batteryOptimizationLauncher.launch(intent)
                } catch (e: Exception) {
                    // Fallback: open battery optimization settings
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                        Toast.makeText(this, "Please disable battery optimization for TiredVPN", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {
                        // Device doesn't support this
                    }
                }
            }
        }
    }

    private fun setupTvMode() {
        if (TvUtils.isTv(this)) {
            // Set initial focus to connect button
            binding.connectButton.requestFocus()
            // Update hint for TV users
            if (TiredVpnService.state.value is VpnState.Disconnected) {
                binding.statusHint.text = "Press OK to Connect"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServerInfo()
    }

    private fun checkConnectOnLaunch() {
        val settingsPrefs = getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
        if (settingsPrefs.getBoolean("connect_on_launch", false)) {
            if (TiredVpnService.state.value is VpnState.Disconnected) {
                connect()
            }
        }
    }

    private fun isFirstLaunch(): Boolean {
        val prefs = getSharedPreferences("tiredvpn_prefs", MODE_PRIVATE)
        val isFirst = prefs.getBoolean("first_launch", true)
        if (isFirst) {
            prefs.edit().putBoolean("first_launch", false).apply()
        }
        return isFirst
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun updateServerInfo() {
        val config = ServerRepository.getActiveServer(this)
        if (config != null) {
            // Show server address initially
            binding.serverName.text = config.name.ifEmpty { config.serverAddress }

            // Fetch country info in background
            countryFetchJob?.cancel()
            countryFetchJob = lifecycleScope.launch {
                val countryInfo = CountryDetector.detectCountry(config.serverAddress)
                // Update UI with country info
                binding.serverFlag.text = countryInfo.flag
                if (config.name.isEmpty() || config.name == "Server" || config.name == config.serverAddress) {
                    binding.serverName.text = countryInfo.name
                }
            }
        } else {
            binding.serverName.text = getString(R.string.select_server)
        }
    }

    private fun setupListeners() {
        // Power button - connect/disconnect
        binding.connectButton.setOnClickListener {
            val currentState = TiredVpnService.state.value
            Log.d(TAG, "=== CONNECT BUTTON CLICKED ===")
            Log.d(TAG, "Current state: $currentState")
            Log.d(TAG, "State class: ${currentState?.javaClass?.simpleName}")

            when (currentState) {
                is VpnState.Disconnected, is VpnState.Error -> {
                    Log.d(TAG, "Action: Calling connect()")
                    connect()
                }
                is VpnState.Connected, is VpnState.Connecting -> {
                    Log.d(TAG, "Action: Calling disconnect()")
                    disconnect()
                }
                else -> {
                    Log.e(TAG, "Unknown state: $currentState")
                }
            }
        }

        // Server selector - open server config
        binding.serverSelector.setOnClickListener {
            startActivity(Intent(this, ServerListActivity::class.java))
        }

        // Settings button
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun observeVpnState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TiredVpnService.state.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: VpnState) {
        when (state) {
            is VpnState.Disconnected -> {
                binding.statusText.text = getString(R.string.disconnected)
                binding.statusHint.text = getString(R.string.tap_to_connect)
                binding.statusHint.visibility = View.VISIBLE
                binding.connectButton.setIconTintResource(R.color.text_primary_dark)
                binding.connectButton.setBackgroundColor(ContextCompat.getColor(this, R.color.button_background))
                binding.connectionInfo.visibility = View.GONE
                // Update mascot to sleeping/disconnected
                binding.mascotImage.setImageResource(R.drawable.sloth_disconnected)
            }
            is VpnState.Connecting -> {
                binding.statusText.text = getString(R.string.connecting)
                binding.statusHint.text = "" // Clear text but keep space
                binding.statusHint.visibility = View.INVISIBLE
                binding.connectButton.setIconTintResource(R.color.connecting)
                binding.connectButton.setBackgroundColor(ContextCompat.getColor(this, R.color.button_background))
                binding.connectionInfo.visibility = View.GONE
            }
            is VpnState.Connected -> {
                binding.statusText.text = getString(R.string.connected)
                // Dark green background with white icon
                binding.connectButton.setIconTintResource(R.color.text_primary_dark)
                binding.connectButton.setBackgroundColor(ContextCompat.getColor(this, R.color.connected_button_background))
                binding.mascotImage.setImageResource(R.drawable.sloth_connected)

                // Show latency and strategy in hint (no server name - it's in the card below)
                val latencyText = if (state.latencyMs > 0) "${state.latencyMs}ms" else ""
                val strategyText = if (state.strategy.isNotEmpty() && state.strategy != "auto") state.strategy else ""
                val hintParts = listOf(latencyText, strategyText).filter { it.isNotEmpty() }
                binding.statusHint.text = hintParts.joinToString(" • ")
                binding.statusHint.visibility = View.VISIBLE
                binding.connectionInfo.visibility = View.GONE
            }
            is VpnState.Error -> {
                binding.statusText.text = getString(R.string.disconnected)
                binding.statusHint.text = state.message
                binding.statusHint.visibility = View.VISIBLE
                binding.connectButton.setIconTintResource(R.color.error)
                binding.connectButton.setBackgroundColor(ContextCompat.getColor(this, R.color.button_background))
                binding.connectionInfo.visibility = View.GONE

                // Ошибка видна в statusHint, отдельный диалог не нужен
            }
        }
    }

    private fun connect() {
        val config = ServerRepository.getActiveServer(this)

        if (config == null || !config.isValid) {
            // No server configured - open list screen
            startActivity(Intent(this, ServerListActivity::class.java))
            return
        }

        // Request VPN permission
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            // System will show consent dialog which handles VPN replacement
            vpnPermissionLauncher.launch(vpnIntent)
        } else if (isOtherVpnActive()) {
            // We have permission but another VPN is active — warn user
            FileLogger.w(TAG, "Another VPN is active, showing warning dialog")
            showOtherVpnActiveDialog()
        } else {
            startVpnService()
        }
    }

    private fun isOtherVpnActive(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        FileLogger.d(TAG, "isOtherVpnActive: $isVpn")
        return isVpn
    }

    private fun showOtherVpnActiveDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.other_vpn_active_title)
            .setMessage(R.string.other_vpn_active_message)
            .setPositiveButton(R.string.open_vpn_settings) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot open VPN settings", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.connect_anyway) { _, _ ->
                FileLogger.w(TAG, "User chose to connect despite active VPN")
                startVpnService()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun startVpnService() {
        val intent = Intent(this, TiredVpnService::class.java).apply {
            action = TiredVpnService.ACTION_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun disconnect() {
        Log.d(TAG, "=== DISCONNECT() METHOD CALLED ===")
        val intent = Intent(this, TiredVpnService::class.java).apply {
            action = TiredVpnService.ACTION_DISCONNECT
        }
        Log.d(TAG, "Sending disconnect intent to TiredVpnService")
        startService(intent)
        Log.d(TAG, "Disconnect intent sent")
    }

    private fun fetchExternalIP() {
        // Cancel any existing job
        ipFetchJob?.cancel()

        // Show loading state
        binding.ipAddressText.text = "Fetching..."

        ipFetchJob = lifecycleScope.launch {
            val ip = withContext(Dispatchers.IO) {
                try {
                    // Try multiple IP services for reliability
                    val services = listOf(
                        "https://api.ipify.org",
                        "https://icanhazip.com",
                        "https://checkip.amazonaws.com"
                    )

                    for (service in services) {
                        try {
                            val result = URL(service).readText().trim()
                            if (result.isNotEmpty() && result.matches(Regex("^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$"))) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            // Try next service
                        }
                    }
                    null
                } catch (e: Exception) {
                    null
                }
            }

            // Update UI on main thread
            if (ip != null) {
                binding.ipAddressText.text = ip
            } else {
                binding.ipAddressText.text = "Unknown"
            }
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            val update = updateManager.checkForUpdate()
            if (update != null) {
                showUpdateDialog(update)
            }
        }
    }

    private fun installDownloadedUpdate() {
        // APK already downloaded by UpdateWorker, just install
        val downloader = com.tiredvpn.android.update.ApkDownloader(this)
        val apk = downloader.getDownloadedApk()

        if (apk != null && apk.exists()) {
            if (!updateManager.canInstall()) {
                // Request permission first
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    installPermissionLauncher.launch(permIntent)
                }
                return
            }

            // Install directly
            val installer = ApkInstaller(this)
            installer.install(apk)
        } else {
            // APK not found, do normal update check
            checkForUpdates()
        }
    }

    private fun showUpdateDialog(config: UpdateConfig) {
        val builder = AlertDialog.Builder(this)
            .setTitle("Доступно обновление ${config.versionName}")
            .setMessage(config.releaseNotes.ifEmpty { "Доступна новая версия приложения" })
            .setPositiveButton("Обновить") { _, _ ->
                startUpdate(config)
            }

        // If force update - no "Later" button and non-cancelable
        if (config.forceUpdate) {
            builder.setCancelable(false)
            builder.setNegativeButton("Выход") { _, _ ->
                finish()
            }
        } else {
            builder.setNegativeButton("Позже", null)
        }

        builder.show()
    }

    private fun startUpdate(config: UpdateConfig) {
        // Check install permission first
        if (!updateManager.canInstall()) {
            pendingForceUpdate = config
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                installPermissionLauncher.launch(intent)
            }
            return
        }

        // Show progress dialog
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Загрузка обновления")
            .setView(ProgressBar(this).apply {
                isIndeterminate = false
                max = 100
            })
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val result = updateManager.downloadAndInstall(config) { progress ->
                progressDialog.findViewById<ProgressBar>(android.R.id.progress)?.progress = progress
            }
            progressDialog.dismiss()

            when (result) {
                is com.tiredvpn.android.update.UpdateResult.DownloadFailed -> {
                    Toast.makeText(this@MainActivity, "Ошибка загрузки", Toast.LENGTH_LONG).show()
                    if (config.forceUpdate) {
                        showUpdateDialog(config) // Retry
                    }
                }
                is com.tiredvpn.android.update.UpdateResult.Error -> {
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
                else -> { /* Installing or NoUpdate */ }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ApkInstaller.REQUEST_INSTALL_PERMISSION) {
            if (updateManager.canInstall()) {
                pendingForceUpdate?.let { startUpdate(it) }
            }
        }
    }
}
