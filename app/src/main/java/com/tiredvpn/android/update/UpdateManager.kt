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

    /** True when the update channel is certificate-pinned; see [UpdateHttp]. */
    val isChannelPinned: Boolean get() = UpdateHttp.isPinned

    /**
     * Check for update without downloading.
     *
     * A failed check lands in [state] as [UpdateState.Error] rather than being
     * flattened into "no update available" — the return value stays nullable for
     * callers that only ask "is there something to install".
     *
     * @return UpdateConfig if available, null otherwise
     */
    suspend fun checkForUpdate(): UpdateConfig? {
        _state.value = UpdateState.Checking
        return when (val result = checker.check()) {
            is VersionCheckResult.UpdateAvailable -> {
                _state.value = UpdateState.UpdateAvailable(result.config)
                result.config
            }

            VersionCheckResult.UpToDate, VersionCheckResult.NotConfigured -> {
                _state.value = UpdateState.Idle
                null
            }

            is VersionCheckResult.Failed -> {
                Log.w(TAG, "Update check failed (${result.kind}): ${result.reason}")
                _state.value = UpdateState.Error("Update check failed: ${result.reason}")
                null
            }
        }
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

            val outcome = downloader.download(config.apkUrl, config.sha256) { progress ->
                _state.value = UpdateState.Downloading(progress)
                onProgress(progress)
            }

            val apk = when (outcome) {
                is DownloadOutcome.Failed -> {
                    // A hash mismatch, a 404 and a dead socket used to arrive here
                    // as the same string. They mean very different things.
                    Log.e(TAG, "Download failed (${outcome.kind}): ${outcome.reason}")
                    _state.value = UpdateState.Error(outcome.reason)
                    return UpdateResult.DownloadFailed(outcome.reason)
                }

                is DownloadOutcome.Success -> outcome.file
            }

            _state.value = UpdateState.ReadyToInstall(config)

            if (!installer.install(apk)) {
                val reason = "downloaded APK is not signed by this app's key"
                _state.value = UpdateState.Error(reason)
                return UpdateResult.Error(reason)
            }
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

    /** @param reason what actually went wrong: hash mismatch, HTTP code, I/O error. */
    data class DownloadFailed(val reason: String) : UpdateResult()

    object Installing : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}
