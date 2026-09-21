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
import android.view.ViewOutlineProvider
import android.graphics.Outline
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
import com.tiredvpn.android.util.BatteryOptimizationHelper
import com.tiredvpn.android.util.CountryDetector
import com.tiredvpn.android.util.TvUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MainActivity : BaseActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** Set by the update notification; consumed once, in onCreate. */
        internal const val EXTRA_INSTALL_UPDATE = "install_update"

        private const val STATE_PENDING_UPDATE = "pending_update"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var updateManager: UpdateManager
    private var countryFetchJob: Job? = null
    private var pendingForceUpdate: UpdateConfig? = null

    /** An already-downloaded APK waiting only for the install permission. */
    private var pendingInstallDownloaded = false

    /** Held so a rotation can dismiss it instead of leaking the window. */
    private var progressDialog: AlertDialog? = null

    // Current connection phase published by the service, plus the ticker that
    // animates it (rotating "Phase." / "Phase.." / "Phase…") while connecting.
    private var currentPhase: String = ""
    private var phaseAnimJob: Job? = null

    // ExoPlayer for the looping muted mascot video shown while Connecting. Created
    // lazily on first need, released in onDestroy. Volume is forced to 0 (the asset
    // has no audio track either, so this is belt-and-suspenders).
    private var mascotPlayer: ExoPlayer? = null

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
        // Check if permission was granted and resume whatever asked for it.
        // There are two askers: an update we offered to download (which leaves
        // pendingForceUpdate set) and a tap on the "update downloaded"
        // notification, where the APK is already on disk and nothing is
        // pending - that second path used to end here doing nothing at all.
        if (updateManager.canInstall()) {
            val pending = pendingForceUpdate
            when {
                pending != null -> startUpdate(pending)
                pendingInstallDownloaded -> installDownloadedUpdate()
            }
        } else if (pendingForceUpdate?.forceUpdate == true) {
            // Force update required but permission denied - close app
            Toast.makeText(this, "Update required to continue", Toast.LENGTH_LONG).show()
            finish()
        }
        pendingInstallDownloaded = false
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

        setupMascotVideoClip()
        requestNotificationPermission()
        requestBatteryOptimizationExemption()
        setupListeners()
        observeVpnState()
        updateServerInfo()
        setupTvMode()

        pendingForceUpdate = savedInstanceState?.let { readUpdateConfig(it) }

        // Check if launched from update notification. The extra is consumed on
        // the way in: it lives on the Activity's Intent, which is handed back
        // on every recreation, so leaving it there restarts the install after
        // each rotation.
        if (intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true) {
            intent.removeExtra(EXTRA_INSTALL_UPDATE)
            installDownloadedUpdate()
        } else {
            checkForUpdates()
        }

        if (!isFirstRun) {
            checkConnectOnLaunch()
        }
    }

    /**
     * Ask once a day at most, not once per onCreate.
     *
     * A user who says no is asked again on the next rotation, the next return
     * from settings, every cold start - which is how a reasonable request turns
     * into something people learn to dismiss. BatteryOptimizationHelper already
     * keeps the "asked at" timestamp; it just wasn't being called.
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (BatteryOptimizationHelper.shouldPromptForExemption(this)) {
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

    override fun onStop() {
        // Don't keep the phase ticker running in the background; observeVpnState's
        // collector restarts it via updateUI() when we return to STARTED.
        stopPhaseAnimation()
        // Don't burn CPU decoding video while not visible; updateUI() resumes it
        // when we return to a Connecting state.
        mascotPlayer?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        // A dialog still attached to a dying window is a WindowLeaked in the
        // log and a frozen screen for the user on the way back.
        dismissProgressDialog()
        mascotPlayer?.release()
        mascotPlayer = null
        super.onDestroy()
    }

    /**
     * Clip the connecting video to the same circle as the static mascot. PlayerView
     * doesn't clip its surface on its own, so we drive it via an outline provider on
     * the texture_view-backed PlayerView.
     */
    private fun setupMascotVideoClip() {
        binding.mascotVideo.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.mascotVideo.clipToOutline = true
    }

    /** Lazily create the looping, muted player bound to the mascot video view. */
    private fun ensureMascotPlayer(): ExoPlayer {
        mascotPlayer?.let { return it }
        val player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri("android.resource://$packageName/${R.raw.sloth_connecting}"))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            prepare()
        }
        binding.mascotVideo.player = player
        mascotPlayer = player
        return player
    }

    private fun startMascotVideo() {
        binding.mascotVideo.visibility = View.VISIBLE
        ensureMascotPlayer().playWhenReady = true
    }

    private fun stopMascotVideo() {
        mascotPlayer?.let {
            it.playWhenReady = false
            it.seekTo(0)
        }
        binding.mascotVideo.visibility = View.GONE
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

        // Long-press = emergency force reset. Lets the user recover a wedged state
        // without having to "force stop" the app from Android settings.
        binding.connectButton.setOnLongClickListener {
            forceReset()
            true
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
                launch {
                    TiredVpnService.state.collect { state ->
                        updateUI(state)
                    }
                }
                // Phase updates only feed currentPhase; the animation ticker (started
                // by updateUI for the Connecting state) is the sole writer of statusText
                // while connecting, so the two collectors never fight over the view.
                launch {
                    TiredVpnService.connectingPhase.collect { phase ->
                        currentPhase = phase
                    }
                }
            }
        }
    }

    private fun startPhaseAnimation() {
        if (phaseAnimJob?.isActive == true) return
        phaseAnimJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                val base = currentPhase.ifEmpty { getString(R.string.connecting) }
                    .trimEnd('…', '.', ' ')
                binding.statusText.text = base + ".".repeat(dots % 4)
                dots++
                delay(400)
            }
        }
    }

    private fun stopPhaseAnimation() {
        phaseAnimJob?.cancel()
        phaseAnimJob = null
    }

    private fun updateUI(state: VpnState) {
        // The phase ticker owns statusText only while Connecting; stop it before any
        // other branch writes to statusText.
        if (state !is VpnState.Connecting) {
            stopPhaseAnimation()
        }
        when (state) {
            is VpnState.Disconnected -> {
                binding.statusText.text = getString(R.string.disconnected)
                binding.statusHint.text = getString(R.string.tap_to_connect)
                binding.statusHint.visibility = View.VISIBLE
                binding.connectButton.setIconTintResource(R.color.text_primary_dark)
                binding.connectButton.setBackgroundColor(ContextCompat.getColor(this, R.color.button_background))
                // Update mascot to sleeping/disconnected
                stopMascotVideo()
                binding.mascotImage.setImageResource(R.drawable.sloth_disconnected)
            }
            is VpnState.Connecting -> {
                // statusText is driven by the animated phase ticker (rotating dots +
                // the current phase such as "Resolving server", "Handshake", …).
                startPhaseAnimation()
                // Play the looping sloth video over the static mascot.
                startMascotVideo()
                binding.statusHint.text = "" // Clear text but keep space
                binding.statusHint.visibility = View.INVISIBLE
                binding.connectButton.setIconTintResource(R.color.connecting)
                binding.connectButton.setBackgroundColor(ContextCompat.getColor(this, R.color.button_background))
            }
            is VpnState.Connected -> {
                binding.statusText.text = getString(R.string.connected)
                // Dark green background with white icon
                binding.connectButton.setIconTintResource(R.color.text_primary_dark)
                binding.connectButton.setBackgroundColor(ContextCompat.getColor(this, R.color.connected_button_background))
                stopMascotVideo()
                binding.mascotImage.setImageResource(R.drawable.sloth_connected)

                // Show latency and strategy in hint (no server name - it's in the card below)
                val latencyText = if (state.latencyMs > 0) "${state.latencyMs}ms" else ""
                val strategyText = if (state.strategy.isNotEmpty() && state.strategy != "auto") state.strategy else ""
                val hintParts = listOf(latencyText, strategyText).filter { it.isNotEmpty() }
                binding.statusHint.text = hintParts.joinToString(" • ")
                binding.statusHint.visibility = View.VISIBLE
            }
            is VpnState.Error -> {
                stopMascotVideo()
                binding.statusText.text = getString(R.string.disconnected)
                binding.statusHint.text = state.message
                binding.statusHint.visibility = View.VISIBLE
                binding.connectButton.setIconTintResource(R.color.error)
                binding.connectButton.setBackgroundColor(ContextCompat.getColor(this, R.color.button_background))

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
        // Paint the connecting look here and nowhere earlier. connect() has
        // three ways to end without starting anything - no server configured,
        // VPN consent refused, "another VPN is active" dismissed - and the
        // service state is a StateFlow that stays Disconnected in all three,
        // so a button painted before the checks stayed "Connecting" forever.
        showConnectingFeedback()

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

    /**
     * Paint the button into the transitional (connecting) look immediately on tap,
     * so the UI never feels frozen while the service spins up. observeVpnState()
     * repaints with the real state shortly after.
     */
    private fun showConnectingFeedback() {
        binding.statusText.text = getString(R.string.connecting)
        binding.statusHint.text = ""
        binding.statusHint.visibility = View.INVISIBLE
        binding.connectButton.setIconTintResource(R.color.connecting)
        binding.connectButton.setBackgroundColor(ContextCompat.getColor(this, R.color.button_background))
    }

    /**
     * Emergency force reset (long-press). Tells the service to wipe the native core
     * and all stuck state so the next connect starts clean — replaces the old
     * "force stop the app in settings" workaround.
     */
    private fun forceReset() {
        Log.d(TAG, "=== FORCE RESET requested ===")
        val intent = Intent(this, TiredVpnService::class.java).apply {
            action = TiredVpnService.ACTION_FORCE_RESET
        }
        startService(intent)
        Toast.makeText(this, getString(R.string.force_reset_toast), Toast.LENGTH_SHORT).show()
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
                // Request permission first, and remember what to come back to.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pendingInstallDownloaded = true
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

        // Keep the config across a recreation: the dialog below is not
        // cancelable, so a rotation mid-download used to lose both the window
        // and any idea of what was being downloaded.
        pendingForceUpdate = config

        // Show progress dialog
        val dialog = AlertDialog.Builder(this)
            .setTitle("Загрузка обновления")
            .setView(ProgressBar(this).apply {
                isIndeterminate = false
                max = 100
            })
            .setCancelable(false)
            .create()
        dialog.show()
        progressDialog = dialog

        lifecycleScope.launch {
            val result = updateManager.downloadAndInstall(config) { progress ->
                dialog.findViewById<ProgressBar>(android.R.id.progress)?.progress = progress
            }
            dismissProgressDialog()

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
                else -> {
                    /* Installing or NoUpdate - nothing left to come back to. */
                    pendingForceUpdate = null
                }
            }
        }
    }

    private fun dismissProgressDialog() {
        progressDialog?.takeIf { it.isShowing }?.dismiss()
        progressDialog = null
    }

    /**
     * pendingForceUpdate is plain state, and the download that sets it runs in
     * lifecycleScope - so a rotation kills the download and, without this,
     * forgets what it was. Saved by hand because UpdateConfig is a plain data
     * class in a package this screen doesn't own.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingForceUpdate?.let { config ->
            outState.putBundle(STATE_PENDING_UPDATE, Bundle().apply {
                putInt("versionCode", config.versionCode)
                putString("versionName", config.versionName)
                putString("apkUrl", config.apkUrl)
                putString("sha256", config.sha256)
                putString("releaseNotes", config.releaseNotes)
                putInt("minAndroidSdk", config.minAndroidSdk)
                putBoolean("forceUpdate", config.forceUpdate)
            })
        }
    }

    private fun readUpdateConfig(state: Bundle): UpdateConfig? {
        val saved = state.getBundle(STATE_PENDING_UPDATE) ?: return null
        return UpdateConfig(
            versionCode = saved.getInt("versionCode"),
            versionName = saved.getString("versionName").orEmpty(),
            apkUrl = saved.getString("apkUrl").orEmpty(),
            sha256 = saved.getString("sha256").orEmpty(),
            releaseNotes = saved.getString("releaseNotes").orEmpty(),
            minAndroidSdk = saved.getInt("minAndroidSdk"),
            forceUpdate = saved.getBoolean("forceUpdate")
        )
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
