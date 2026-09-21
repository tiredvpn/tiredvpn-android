package com.tiredvpn.android.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.tiredvpn.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Asks the update server what the newest build is.
 *
 * Everything the check depends on arrives through the constructor so the class
 * can be driven against a MockWebServer: the endpoint, the HTTP client, the
 * installed version code and the SDK level it compares against. The secondary
 * constructor wires the production values.
 */
class VersionChecker(
    private val endpoint: String,
    private val currentVersionCode: () -> Int,
    private val client: OkHttpClient = UpdateHttp.metadataClient,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) {

    constructor(context: Context) : this(
        endpoint = BuildConfig.UPDATE_URL,
        currentVersionCode = { installedVersionCode(context) }
    )

    companion object {
        private const val TAG = "VersionChecker"

        fun installedVersionCode(context: Context): Int = try {
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

    /**
     * Check for an available update.
     *
     * Never throws: every failure comes back as [VersionCheckResult.Failed] with
     * a reason and a retry policy, so callers can tell "nothing new" apart from
     * "we did not find out".
     */
    suspend fun check(): VersionCheckResult = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) {
            Log.d(TAG, "UPDATE_URL not configured, skipping update check")
            return@withContext VersionCheckResult.NotConfigured
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Cache-Control", "no-cache")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val kind = failureKindForHttp(response.code)
                    Log.w(TAG, "Update manifest returned HTTP ${response.code} ($kind)")
                    return@use VersionCheckResult.Failed(kind, "HTTP ${response.code}")
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    Log.w(TAG, "Empty response from update server")
                    return@use VersionCheckResult.Failed(
                        FailureKind.TRANSIENT,
                        "empty response body"
                    )
                }

                evaluate(parse(body))
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Update manifest is not valid JSON", e)
            VersionCheckResult.Failed(FailureKind.PERMANENT, "malformed manifest: ${e.message}")
        } catch (e: IOException) {
            Log.w(TAG, "Update check could not reach the server", e)
            VersionCheckResult.Failed(FailureKind.TRANSIENT, "network error: ${e.message}")
        }
    }

    /**
     * @return the update if one is available, null for every other outcome.
     *         Kept for callers that only branch on "is there an update"; use
     *         [check] when the difference between "no" and "do not know" matters.
     */
    suspend fun checkForUpdate(): UpdateConfig? =
        (check() as? VersionCheckResult.UpdateAvailable)?.config

    /** @throws JSONException when a required field is missing or mistyped. */
    private fun parse(body: String): UpdateConfig {
        val json = JSONObject(body)
        return UpdateConfig(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = json.getString("apkUrl"),
            sha256 = json.getString("sha256"),
            releaseNotes = json.optString("releaseNotes", ""),
            minAndroidSdk = json.optInt("minAndroidSdk", 24),
            forceUpdate = json.optBoolean("forceUpdate", false)
        )
    }

    private fun evaluate(config: UpdateConfig): VersionCheckResult {
        if (!config.apkUrl.startsWith("https://", ignoreCase = true)) {
            // A manifest that points at plain HTTP downgrades the only transport
            // guarantee this channel has. Do not follow it.
            Log.e(TAG, "Update manifest points at a non-HTTPS APK URL, refusing")
            return VersionCheckResult.Failed(FailureKind.PERMANENT, "apkUrl is not https")
        }

        if (UpdateHttp.isPinned &&
            UpdateHttp.hostOf(config.apkUrl) != UpdateHttp.hostOf(endpoint)
        ) {
            // Not fatal — the pin covers the manifest host only, and hosting the
            // APK on a CDN is legitimate — but the operator should know the pin
            // stops short of the bytes being installed.
            Log.w(
                TAG,
                "APK is served from ${UpdateHttp.hostOf(config.apkUrl)}, outside the pinned " +
                    "host ${UpdateHttp.hostOf(endpoint)}; the certificate pin does not cover it"
            )
        }

        val installed = currentVersionCode()
        Log.d(TAG, "Current: $installed, Remote: ${config.versionCode}")

        if (config.versionCode <= installed) {
            Log.d(TAG, "No update available")
            return VersionCheckResult.UpToDate
        }

        if (sdkInt < config.minAndroidSdk) {
            Log.i(
                TAG,
                "Update ${config.versionName} needs SDK ${config.minAndroidSdk}, this device is $sdkInt"
            )
            return VersionCheckResult.UpToDate
        }

        Log.i(TAG, "Update available: ${config.versionName} (force=${config.forceUpdate})")
        return VersionCheckResult.UpdateAvailable(config)
    }
}
