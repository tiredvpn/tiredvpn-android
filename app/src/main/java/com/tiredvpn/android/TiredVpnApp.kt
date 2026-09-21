package com.tiredvpn.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tiredvpn.android.update.UpdateHttp
import com.tiredvpn.android.update.UpdateWorker
import com.tiredvpn.android.util.FileLogger

class TiredVpnApp : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "TiredVpnApp"
        const val VPN_NOTIFICATION_CHANNEL_ID = "tiredvpn_vpn_status_v2"
        private const val OLD_VPN_NOTIFICATION_CHANNEL_ID = "tiredvpn_vpn_status"
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        createNotificationChannels()

        // Schedule background update checks every 6 hours.
        // WorkManager is auto-initialized via Configuration.Provider.
        //
        // Scheduling unconditionally left WorkManager holding a periodic job on
        // builds that have no update channel at all — it woke up every six hours
        // only to find UPDATE_URL empty and exit. The cancel branch also cleans
        // up after a build that did have one.
        if (UpdateWorker.isSelfUpdateEnabled) {
            UpdateWorker.schedule(this)
            if (!UpdateHttp.isPinned) {
                FileLogger.w(
                    TAG,
                    "Self-update is on but the channel is not pinned (UPDATE_SERVER_PIN is " +
                        "empty): the update chain of trust is whatever CA the device accepts"
                )
            }
        } else {
            // The public OSS build ships with UPDATE_URL empty, so this is its
            // normal state, not a misconfiguration. Say which of the two reasons
            // it is instead of leaving someone to guess why nothing updates.
            UpdateWorker.cancel(this)
            FileLogger.i(
                TAG,
                "Self-update is off (" +
                    (if (!BuildConfig.SELF_UPDATE_ENABLED) "built with -PselfUpdate=false"
                    else "UPDATE_URL is empty") +
                    "), update checks are not scheduled"
            )
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Delete old channel with IMPORTANCE_MIN (if exists)
            notificationManager.deleteNotificationChannel(OLD_VPN_NOTIFICATION_CHANNEL_ID)

            val vpnChannel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT  // Default - shows notifications without sound
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }

            notificationManager.createNotificationChannel(vpnChannel)
        }
    }
}
