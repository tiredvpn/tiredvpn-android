package com.tiredvpn.android.update

import android.util.Log
import com.tiredvpn.android.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The one place where the update channel's HTTP client is built.
 *
 * Worth being blunt about what this channel is worth: apkUrl and sha256 arrive
 * in the same JSON from the same host, so the hash is not an independent check
 * on the APK — it only proves the bytes survived the wire. Everything rests on
 * whoever answers at [BuildConfig.UPDATE_URL]. Certificate pinning narrows that
 * down, and it is off whenever UPDATE_SERVER_PIN is empty, which is the default
 * in app/build.gradle.kts. That case is logged loudly rather than failed open in
 * silence, and [isPinned] is readable so the rest of the app can say so too.
 *
 * The second line of defence lives in [ApkSignatureGuard]: whatever arrives has
 * to be signed by the same key as the app that is already installed.
 */
object UpdateHttp {

    private const val TAG = "UpdateHttp"

    /** True when the update channel is pinned to a known certificate. */
    val isPinned: Boolean =
        BuildConfig.UPDATE_SERVER_PIN.isNotBlank() && BuildConfig.UPDATE_URL.isNotBlank()

    /** Short timeouts: this call fetches a few hundred bytes of JSON. */
    val metadataClient: OkHttpClient by lazy {
        base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Long read timeout: this call pulls a multi-megabyte APK. */
    val downloadClient: OkHttpClient by lazy { base }

    /** Host part of a URL, for pinning and for comparing the APK host with the manifest host. */
    fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore("/")

    private val base: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)

        when {
            BuildConfig.UPDATE_URL.isBlank() ->
                Log.i(TAG, "Update channel not configured (UPDATE_URL is empty)")

            isPinned -> {
                val host = hostOf(BuildConfig.UPDATE_URL)
                builder.certificatePinner(
                    CertificatePinner.Builder()
                        .add(host, "sha256/${BuildConfig.UPDATE_SERVER_PIN}")
                        .build()
                )
                Log.i(TAG, "Update channel pinned to $host")
            }

            else -> Log.w(
                TAG,
                "UPDATE CHANNEL IS NOT PINNED: UPDATE_SERVER_PIN is empty, so the whole " +
                    "update chain of trust is whatever CA the device happens to accept for " +
                    "${hostOf(BuildConfig.UPDATE_URL)}. Build with -PupdateServerPin=<sha256> to fix."
            )
        }

        builder.build()
    }
}
