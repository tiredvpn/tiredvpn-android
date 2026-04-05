package com.tiredvpn.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tiredvpn.android.update.UpdateWorker
import com.tiredvpn.android.util.FileLogger

class TiredVpnApp : Application(), Configuration.Provider {

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "tiredvpn_vpn_status_v2"
        private const val OLD_VPN_NOTIFICATION_CHANNEL_ID = "tiredvpn_vpn_status"
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        createNotificationChannels()

        // Schedule background update checks every 6 hours
        // WorkManager is auto-initialized via Configuration.Provider
        UpdateWorker.schedule(this)
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
