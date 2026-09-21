package com.tiredvpn.android.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.tiredvpn.android.BuildConfig
import com.tiredvpn.android.R
import com.tiredvpn.android.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Background worker for the built-in updater, in two phases.
 *
 * The phases are separate pieces of work because they have different appetites.
 * Checking costs a few hundred bytes of JSON and should happen on whatever
 * network exists. Downloading costs an entire APK and has no business running on
 * a metered connection, so the check enqueues a second, unmetered job instead of
 * pulling the file itself.
 */
class UpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UpdateWorker"

        private const val CHECK_WORK_NAME = "update_check"
        private const val DOWNLOAD_WORK_NAME = "update_download"

        private const val CHANNEL_ID = "updates"
        private const val PROGRESS_CHANNEL_ID = "updates_progress"
        private const val NOTIFICATION_ID = 9999
        private const val PROGRESS_NOTIFICATION_ID = 9998

        /**
         * requestCode for the "update ready" notification's PendingIntent.
         *
         * Extras are not part of a PendingIntent's identity, so two
         * `getActivity(context, 0, MainActivity, FLAG_UPDATE_CURRENT)` calls are
         * the same PendingIntent and the later one overwrites the earlier one's
         * extras. The VPN notification in TiredVpnService uses requestCode 0;
         * this one is reserved for the updater and must stay distinct from it.
         */
        const val UPDATE_REQUEST_CODE = 9001

        /** Second half of the same fix: a distinct action keeps the intents from matching. */
        const val ACTION_INSTALL_UPDATE = "com.tiredvpn.android.action.INSTALL_UPDATE"

        private const val KEY_PHASE = "phase"
        private const val PHASE_CHECK = "check"
        private const val PHASE_DOWNLOAD = "download"
        private const val KEY_APK_URL = "apkUrl"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_VERSION_NAME = "versionName"
        private const val KEY_RELEASE_NOTES = "releaseNotes"

        /**
         * Whether this build updates itself at all: the Play build compiles
         * SELF_UPDATE_ENABLED to false, and any build without an UPDATE_URL has
         * nowhere to ask.
         */
        val isSelfUpdateEnabled: Boolean
            get() = BuildConfig.SELF_UPDATE_ENABLED && BuildConfig.UPDATE_URL.isNotBlank()

        /**
         * Schedule periodic update checks (every 6 hours).
         *
         * Uses UPDATE rather than KEEP: with KEEP, a device that installed an
         * older build keeps that build's interval, constraints and initial delay
         * forever, so schedule changes never reach the installed base.
         */
        fun schedule(context: Context) {
            if (!isSelfUpdateEnabled) {
                Log.d(TAG, "Self-update is disabled in this build, not scheduling")
                cancel(context)
                return
            }

            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(1, TimeUnit.HOURS) // First check after 1 hour
                .setInputData(workDataOf(KEY_PHASE to PHASE_CHECK))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    CHECK_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )

            Log.d(TAG, "Update worker scheduled")
        }

        /**
         * Run immediate check
         */
        fun checkNow(context: Context) {
            if (!isSelfUpdateEnabled) return

            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_PHASE to PHASE_CHECK))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * Cancel scheduled checks
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(CHECK_WORK_NAME)
                cancelUniqueWork(DOWNLOAD_WORK_NAME)
            }
        }
    }

    private val notificationManager
        get() = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        if (!isSelfUpdateEnabled) {
            // Reachable when a build with self-update turned off inherits work
            // that an earlier build enqueued.
            Log.d(TAG, "Self-update is disabled in this build, dropping enqueued work")
            cancel(applicationContext)
            return Result.success()
        }

        return when (inputData.getString(KEY_PHASE)) {
            PHASE_DOWNLOAD -> runDownloadPhase()
            else -> runCheckPhase()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // WorkManager can ask for this before doWork() runs, so the channel has
        // to exist by now or the notification is dropped on API 26+.
        createChannels()
        return foregroundInfo(buildProgressNotification(0))
    }

    // --- Phase 1: metadata, any network ---

    private suspend fun runCheckPhase(): Result {
        Log.d(TAG, "Checking for updates...")

        return when (val result = VersionChecker(applicationContext).check()) {
            is VersionCheckResult.UpdateAvailable -> {
                Log.i(TAG, "Update available: ${result.config.versionName}")
                enqueueDownload(result.config)
                Result.success()
            }

            VersionCheckResult.UpToDate, VersionCheckResult.NotConfigured -> {
                Log.d(TAG, "No update available")
                Result.success()
            }

            is VersionCheckResult.Failed -> finish(result.kind, "check", result.reason)
        }
    }

    private fun enqueueDownload(config: UpdateConfig) {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(
                Constraints.Builder()
                    // The APK is tens of megabytes. Never on someone's mobile data.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    KEY_PHASE to PHASE_DOWNLOAD,
                    KEY_APK_URL to config.apkUrl,
                    KEY_SHA256 to config.sha256,
                    KEY_VERSION_NAME to config.versionName,
                    KEY_RELEASE_NOTES to config.releaseNotes
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(DOWNLOAD_WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        Log.i(TAG, "Download of ${config.versionName} queued for an unmetered network")
    }

    // --- Phase 2: the APK itself, unmetered only ---

    private suspend fun runDownloadPhase(): Result {
        val url = inputData.getString(KEY_APK_URL)
        val sha256 = inputData.getString(KEY_SHA256)
        if (url.isNullOrBlank() || sha256.isNullOrBlank()) {
            Log.e(TAG, "Download phase started without apkUrl/sha256")
            return Result.failure()
        }

        val versionName = inputData.getString(KEY_VERSION_NAME).orEmpty()
        val releaseNotes = inputData.getString(KEY_RELEASE_NOTES).orEmpty()

        createChannels()
        enterForeground()

        var lastShown = 0
        val outcome = ApkDownloader(applicationContext).download(url, sha256) { progress ->
            // The callback is not a suspending context, so the foreground
            // notification is refreshed through the NotificationManager under the
            // same id instead of another setForeground() call.
            if (progress >= lastShown + 5) {
                lastShown = progress
                notificationManager.notify(
                    PROGRESS_NOTIFICATION_ID,
                    buildProgressNotification(progress)
                )
            }
        }

        notificationManager.cancel(PROGRESS_NOTIFICATION_ID)

        val apk = when (outcome) {
            is DownloadOutcome.Failed -> return finish(outcome.kind, "download", outcome.reason)
            is DownloadOutcome.Success -> outcome.file
        }

        if (!ApkSignatureGuard.verify(applicationContext, apk)) {
            // Do not advertise an update the installer will refuse, and do not
            // leave the file sitting in the cache for MainActivity to pick up.
            apk.delete()
            return finish(
                FailureKind.PERMANENT,
                "download",
                "downloaded APK is not signed by this app's key"
            )
        }

        Log.i(TAG, "APK downloaded: ${apk.absolutePath}")
        showUpdateNotification(versionName, releaseNotes)
        return Result.success()
    }

    /**
     * Translate a failure into a WorkManager verdict.
     *
     * Everything used to come back as retry(), so a 404 or a hash mismatch was
     * rescheduled for as long as the app stayed installed.
     */
    private fun finish(kind: FailureKind, phase: String, reason: String): Result = when (kind) {
        FailureKind.TRANSIENT -> {
            Log.w(TAG, "Update $phase failed, will retry: $reason")
            Result.retry()
        }

        FailureKind.PERMANENT -> {
            Log.e(TAG, "Update $phase failed permanently, giving up: $reason")
            Result.failure()
        }
    }

    // --- Notifications ---

    private suspend fun enterForeground() {
        try {
            setForeground(foregroundInfo(buildProgressNotification(0)))
        } catch (e: Exception) {
            // Android 12+ refuses to start a foreground service while the app is
            // in the background, and WorkManager surfaces that as an exception.
            // Losing the notification is survivable; the download then runs as
            // ordinary work and risks being cut at the ten-minute mark.
            Log.w(TAG, "Foreground mode unavailable, downloading as background work", e)
        }
    }

    private fun foregroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }

    private fun buildProgressNotification(progress: Int) =
        NotificationCompat.Builder(applicationContext, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle(applicationContext.getString(R.string.update_download_title))
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.update_available_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = applicationContext.getString(R.string.update_available_channel_desc) }
        )

        notificationManager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                applicationContext.getString(R.string.update_download_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.update_download_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun showUpdateNotification(versionName: String, releaseNotes: String) {
        createChannels()

        // Intent to open app and trigger install
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = ACTION_INSTALL_UPDATE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("install_update", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            UPDATE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle(applicationContext.getString(R.string.update_available_title, versionName))
            .setContentText(releaseNotes.ifEmpty { applicationContext.getString(R.string.update_available_text) })
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Update notification shown")
    }
}
