package com.tiredvpn.android.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.tiredvpn.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks for available updates from remote server
 */
class VersionChecker(private val context: Context) {

    companion object {
        private const val TAG = "VersionChecker"
        private val UPDATE_URL = BuildConfig.UPDATE_URL
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .apply {
            val pin = BuildConfig.UPDATE_SERVER_PIN
            if (pin.isNotBlank()) {
                val host = UPDATE_URL.removePrefix("https://").removePrefix("http://")
                    .substringBefore("/")
                certificatePinner(
                    okhttp3.CertificatePinner.Builder()
                        .add(host, "sha256/$pin")
                        .build()
                )
            }
        }
        .build()

    /**
     * Check for available update
     * @return UpdateConfig if update is available, null otherwise
     */
    suspend fun checkForUpdate(): UpdateConfig? = withContext(Dispatchers.IO) {
        if (UPDATE_URL.isBlank()) {
            Log.d(TAG, "UPDATE_URL not configured, skipping update check")
            return@withContext null
        }
        try {
            val request = Request.Builder()
                .url(UPDATE_URL)
                .header("Cache-Control", "no-cache")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to check update: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string()
            if (body.isNullOrEmpty()) {
                Log.w(TAG, "Empty response from update server")
                return@withContext null
            }

            val json = JSONObject(body)
            val config = UpdateConfig(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256"),
                releaseNotes = json.optString("releaseNotes", ""),
                minAndroidSdk = json.optInt("minAndroidSdk", 24),
                forceUpdate = json.optBoolean("forceUpdate", false)
            )

            val currentVersionCode = getCurrentVersionCode()
            Log.d(TAG, "Current: $currentVersionCode, Remote: ${config.versionCode}")

            // Check if update is available and compatible
            if (config.versionCode > currentVersionCode &&
                Build.VERSION.SDK_INT >= config.minAndroidSdk) {
                Log.i(TAG, "Update available: ${config.versionName} (force=${config.forceUpdate})")
                config
            } else {
                Log.d(TAG, "No update available")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update", e)
            null
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current version", e)
            0
        }
    }
}
