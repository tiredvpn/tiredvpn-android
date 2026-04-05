package com.tiredvpn.android.update

import android.content.Context
import android.util.Log
import com.tiredvpn.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads APK files with progress reporting and SHA256 verification
 */
class ApkDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ApkDownloader"
        private const val UPDATES_DIR = "updates"
        private const val APK_FILENAME = "update.apk"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .apply {
            val pin = BuildConfig.UPDATE_SERVER_PIN
            val updateUrl = BuildConfig.UPDATE_URL
            if (pin.isNotBlank() && updateUrl.isNotBlank()) {
                val host = updateUrl.removePrefix("https://").removePrefix("http://")
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
     * Download APK file with progress callback
     * @param url APK download URL
     * @param expectedSha256 Expected SHA256 hash for verification
     * @param onProgress Progress callback (0-100)
     * @return Downloaded file if successful and verified, null otherwise
     */
    suspend fun download(
        url: String,
        expectedSha256: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, UPDATES_DIR).apply {
                if (!exists()) mkdirs()
            }
            val apkFile = File(cacheDir, APK_FILENAME)

            // Delete old file if exists
            if (apkFile.exists()) {
                apkFile.delete()
            }

            Log.d(TAG, "Starting download: $url")

            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: ${response.code}")
                return@withContext null
            }

            val body = response.body ?: run {
                Log.e(TAG, "Empty response body")
                return@withContext null
            }

            val total = body.contentLength()
            var downloaded = 0L

            apkFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val progress = (downloaded * 100 / total).toInt()
                            onProgress(progress)
                        }
                    }
                }
            }

            Log.d(TAG, "Download complete: ${apkFile.length()} bytes")

            // Verify SHA256
            val actualSha256 = apkFile.sha256()
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                Log.e(TAG, "SHA256 mismatch! Expected: $expectedSha256, Actual: $actualSha256")
                apkFile.delete()
                return@withContext null
            }

            Log.i(TAG, "SHA256 verified successfully")
            apkFile

        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            null
        }
    }

    /**
     * Get path to downloaded APK if exists
     */
    fun getDownloadedApk(): File? {
        val apkFile = File(File(context.cacheDir, UPDATES_DIR), APK_FILENAME)
        return if (apkFile.exists()) apkFile else null
    }

    /**
     * Delete downloaded APK
     */
    fun clearCache() {
        try {
            File(context.cacheDir, UPDATES_DIR).deleteRecursively()
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
