package com.tiredvpn.android.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads APK files with progress reporting and SHA256 verification.
 *
 * The cache root and the HTTP client are constructor parameters so the download
 * path can be exercised against a MockWebServer and a temporary directory; the
 * [Context] constructor wires the production values.
 */
class ApkDownloader(
    private val cacheRoot: File,
    private val client: OkHttpClient = UpdateHttp.downloadClient
) {

    constructor(context: Context) : this(context.cacheDir)

    companion object {
        private const val TAG = "ApkDownloader"
        private const val UPDATES_DIR = "updates"
        private const val APK_FILENAME = "update.apk"
    }

    /**
     * Download an APK and verify it against [expectedSha256].
     *
     * @param onProgress called with 0-100 while bytes arrive, only when the
     *        server sent a Content-Length.
     * @return the file on success, otherwise why it failed and whether a retry
     *         could help. Never throws.
     */
    suspend fun download(
        url: String,
        expectedSha256: String,
        onProgress: (Int) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            Log.e(TAG, "Refusing to download an APK over a non-HTTPS URL")
            return@withContext DownloadOutcome.Failed(
                FailureKind.PERMANENT,
                "apkUrl is not https"
            )
        }

        val apkFile = File(File(cacheRoot, UPDATES_DIR).apply { mkdirs() }, APK_FILENAME)
        apkFile.delete()

        Log.d(TAG, "Starting download: $url")
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val kind = failureKindForHttp(response.code)
                    Log.e(TAG, "Download failed: HTTP ${response.code} ($kind)")
                    return@use DownloadOutcome.Failed(kind, "HTTP ${response.code}")
                }

                val body = response.body
                if (body == null) {
                    Log.e(TAG, "Empty response body")
                    return@use DownloadOutcome.Failed(
                        FailureKind.TRANSIENT,
                        "empty response body"
                    )
                }

                val total = body.contentLength()
                var downloaded = 0L
                var lastReported = -1

                apkFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val progress = (downloaded * 100 / total).toInt()
                                if (progress != lastReported) {
                                    lastReported = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                Log.d(TAG, "Download complete: ${apkFile.length()} bytes")

                val actualSha256 = apkFile.sha256()
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA256 mismatch! Expected: $expectedSha256, Actual: $actualSha256")
                    apkFile.delete()
                    return@use DownloadOutcome.Failed(
                        FailureKind.PERMANENT,
                        "SHA-256 mismatch: expected $expectedSha256, got $actualSha256"
                    )
                }

                Log.i(TAG, "SHA256 verified successfully")
                DownloadOutcome.Success(apkFile)
            }
        } catch (e: IOException) {
            // Covers both the socket and the local file: a full disk and a
            // dropped connection are equally worth another attempt later.
            Log.w(TAG, "Download interrupted", e)
            apkFile.delete()
            DownloadOutcome.Failed(FailureKind.TRANSIENT, "I/O error: ${e.message}")
        }
    }

    /**
     * Get path to downloaded APK if exists
     */
    fun getDownloadedApk(): File? {
        val apkFile = File(File(cacheRoot, UPDATES_DIR), APK_FILENAME)
        return if (apkFile.exists()) apkFile else null
    }

    /**
     * Delete downloaded APK
     */
    fun clearCache() {
        try {
            File(cacheRoot, UPDATES_DIR).deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
