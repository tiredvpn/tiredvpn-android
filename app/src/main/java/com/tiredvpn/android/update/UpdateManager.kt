package com.tiredvpn.android.update

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main update manager that coordinates checking, downloading and installing updates
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
    }

    private val checker = VersionChecker(context)
    private val downloader = ApkDownloader(context)
    private val installer = ApkInstaller(context)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Check for update without downloading
     * @return UpdateConfig if available, null otherwise
     */
    suspend fun checkForUpdate(): UpdateConfig? {
        _state.value = UpdateState.Checking
        val update = checker.checkForUpdate()
        _state.value = if (update != null) {
            UpdateState.UpdateAvailable(update)
        } else {
            UpdateState.Idle
        }
        return update
    }

    /**
     * Download and install update
     * @param config Update configuration
     * @param onProgress Progress callback (0-100)
     * @return UpdateResult indicating success or failure
     */
    suspend fun downloadAndInstall(
        config: UpdateConfig,
        onProgress: (Int) -> Unit = {}
    ): UpdateResult {
        try {
            _state.value = UpdateState.Downloading(0)

            val apk = downloader.download(config.apkUrl, config.sha256) { progress ->
                _state.value = UpdateState.Downloading(progress)
                onProgress(progress)
            }

            if (apk == null) {
                Log.e(TAG, "Download failed or SHA256 mismatch")
                _state.value = UpdateState.Error("Download failed")
                return UpdateResult.DownloadFailed
            }

            _state.value = UpdateState.ReadyToInstall(config)

            installer.install(apk)
            return UpdateResult.Installing

        } catch (e: Exception) {
            Log.e(TAG, "Update failed", e)
            _state.value = UpdateState.Error(e.message ?: "Unknown error")
            return UpdateResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Check if app can install packages
     */
    fun canInstall(): Boolean = installer.canInstall()

    /**
     * Request permission to install packages
     */
    fun requestInstallPermission(activity: Activity) {
        installer.requestInstallPermission(activity)
    }

    /**
     * Clear downloaded update files
     */
    fun clearCache() {
        downloader.clearCache()
        _state.value = UpdateState.Idle
    }

    /**
     * Reset state to idle
     */
    fun reset() {
        _state.value = UpdateState.Idle
    }
}

/**
 * Update state for UI observation
 */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val config: UpdateConfig) : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data class ReadyToInstall(val config: UpdateConfig) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * Result of update operation
 */
sealed class UpdateResult {
    object NoUpdate : UpdateResult()
    object DownloadFailed : UpdateResult()
    object Installing : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}
