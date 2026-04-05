package com.tiredvpn.android.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.tiredvpn.android.R
import com.tiredvpn.android.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Background worker that periodically checks for updates,
 * downloads APK silently, and shows notification when ready
 */
class UpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "UpdateWorker"
        private const val WORK_NAME = "update_check"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 9999

        /**
         * Schedule periodic update checks (every 6 hours)
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS) // First check after 1 hour
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.d(TAG, "Update worker scheduled")
        }

        /**
         * Run immediate check
         */
        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * Cancel scheduled checks
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    private val checker = VersionChecker(applicationContext)
    private val downloader = ApkDownloader(applicationContext)

    override suspend fun doWork(): Result {
        Log.d(TAG, "Checking for updates...")

        try {
            // Check if update available
            val update = checker.checkForUpdate() ?: run {
                Log.d(TAG, "No update available")
                return Result.success()
            }

            Log.i(TAG, "Update available: ${update.versionName}")

            // Download APK silently
            val apk = downloader.download(update.apkUrl, update.sha256) { progress ->
                Log.d(TAG, "Download progress: $progress%")
            }

            if (apk == null) {
                Log.e(TAG, "Download failed")
                return Result.retry()
            }

            Log.i(TAG, "APK downloaded: ${apk.absolutePath}")

            // Show notification
            showUpdateNotification(update)

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            return Result.retry()
        }
    }

    private fun showUpdateNotification(config: UpdateConfig) {
        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновления",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о новых версиях приложения"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open app and trigger install
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("install_update", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_upload)
            .setContentTitle("Доступно обновление ${config.versionName}")
            .setContentText(config.releaseNotes.ifEmpty { "Нажмите для установки" })
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Update notification shown")
    }
}
