package com.tiredvpn.android.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handles APK installation using FileProvider for Android 7+
 */
class ApkInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ApkInstaller"
        const val REQUEST_INSTALL_PERMISSION = 1001
    }

    /**
     * Install APK file using system installer
     * @param apkFile The APK file to install
     */
    fun install(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            Log.i(TAG, "Starting APK installation: ${apkFile.name}")
            context.startActivity(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start installation", e)
            throw e
        }
    }

    /**
     * Check if app can install APK files
     * On Android 8+ requires REQUEST_INSTALL_PACKAGES permission
     */
    fun canInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Request permission to install APK files
     * Only needed on Android 8+
     * @param activity Activity to receive result
     */
    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQUEST_INSTALL_PERMISSION)
                Log.d(TAG, "Requesting install permission")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request install permission", e)
            }
        }
    }
}
