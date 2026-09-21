package com.tiredvpn.android.update

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Drives [ApkDownloader] against a real HTTPS server and a real directory.
 *
 * The server speaks TLS with a self-signed certificate the test client trusts,
 * because the downloader refuses plain http outright — worth keeping that way
 * rather than making the guard optional so the test can reach past it.
 *
 * The point of interest is the SHA-256 gate: before this existed, nothing proved
 * the downloader actually rejected a file whose hash did not match.
 */
class ApkDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val payload = "PK pretend this is an APK".toByteArray()

    private val payloadSha256: String
        get() = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()

        client = OkHttpClient.Builder()
            .sslSocketFactory(
                clientCertificates.sslSocketFactory(),
                clientCertificates.trustManager
            )
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun downloader() = ApkDownloader(tempFolder.root, client)

    private fun apkOnDisk(): File = File(File(tempFolder.root, "updates"), "update.apk")

    private fun apkUrl(): String = server.url("/app.apk").toString()

    private fun enqueueBody(bytes: ByteArray) {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())
    }

    // --- The gate ---

    @Test
    fun `matching sha256 is accepted and written to disk`() = runBlocking {
        enqueueBody(payload)

        val outcome = downloader().download(apkUrl(), payloadSha256) {}

        assertTrue("expected Success, got $outcome", outcome is DownloadOutcome.Success)
        assertTrue(apkOnDisk().exists())
        assertEquals(payload.size.toLong(), apkOnDisk().length())
    }

    @Test
    fun `mismatched sha256 is rejected and the file is deleted`() = runBlocking {
        enqueueBody(payload)

        val outcome = downloader().download(apkUrl(), "0".repeat(64)) {}

        assertTrue("expected Failed, got $outcome", outcome is DownloadOutcome.Failed)
        assertEquals(FailureKind.PERMANENT, (outcome as DownloadOutcome.Failed).kind)
        assertTrue(
            "reason should name the mismatch, was: ${outcome.reason}",
            outcome.reason.contains("SHA-256", ignoreCase = true)
        )
        assertFalse("a rejected APK must not stay in the cache", apkOnDisk().exists())
        assertNull(downloader().getDownloadedApk())
    }

    @Test
    fun `sha256 comparison is case insensitive`() = runBlocking {
        enqueueBody(payload)

        val outcome = downloader().download(apkUrl(), payloadSha256.uppercase()) {}

        assertTrue("expected Success, got $outcome", outcome is DownloadOutcome.Success)
    }

    // --- Retry policy ---

    @Test
    fun `404 on the apk is permanent`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("gone").build())

        val outcome = downloader().download(apkUrl(), payloadSha256) {}

        assertEquals(FailureKind.PERMANENT, (outcome as DownloadOutcome.Failed).kind)
    }

    @Test
    fun `502 on the apk is transient`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(502).body("bad gateway").build())

        val outcome = downloader().download(apkUrl(), payloadSha256) {}

        assertEquals(FailureKind.TRANSIENT, (outcome as DownloadOutcome.Failed).kind)
    }

    @Test
    fun `unreachable server is transient`() = runBlocking {
        val url = apkUrl()
        server.close()

        val outcome = downloader().download(url, payloadSha256) {}

        assertEquals(FailureKind.TRANSIENT, (outcome as DownloadOutcome.Failed).kind)
    }

    // --- Refusals ---

    @Test
    fun `plain http url is refused without a request`() = runBlocking {
        val outcome = downloader().download(
            "http://updates.example.com/app.apk",
            payloadSha256
        ) {}

        assertEquals(FailureKind.PERMANENT, (outcome as DownloadOutcome.Failed).kind)
        assertEquals(0, server.requestCount)
    }

    // --- Progress ---

    @Test
    fun `progress is reported up to 100 and never goes backwards`() = runBlocking {
        enqueueBody(payload)
        val seen = mutableListOf<Int>()

        downloader().download(apkUrl(), payloadSha256) { seen += it }

        assertEquals(100, seen.lastOrNull())
        assertEquals(seen.sorted(), seen)
    }
}
