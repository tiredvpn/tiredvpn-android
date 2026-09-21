package com.tiredvpn.android.update

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Drives [VersionChecker] against a real HTTP server.
 *
 * Before the constructor took its endpoint and client as parameters, none of
 * this was reachable: both were read from BuildConfig into private fields at
 * construction time.
 */
class VersionCheckerTest {

    private lateinit var server: MockWebServer

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun checker(
        installedVersionCode: Int = 10,
        sdkInt: Int = 34,
        path: String = "/t.json"
    ) = VersionChecker(
        endpoint = server.url(path).toString(),
        currentVersionCode = { installedVersionCode },
        client = client,
        sdkInt = sdkInt
    )

    private fun manifest(
        versionCode: Int = 11,
        apkUrl: String = "https://updates.example.com/app.apk",
        sha256: String = "deadbeef"
    ) = """
        {
          "versionCode": $versionCode,
          "versionName": "1.1.0",
          "apkUrl": "$apkUrl",
          "sha256": "$sha256",
          "releaseNotes": "Bug fixes"
        }
    """.trimIndent()

    private fun enqueue(code: Int = 200, body: String = "") {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    // --- The happy path ---

    @Test
    fun `newer versionCode is reported as available`() = runBlocking {
        enqueue(body = manifest(versionCode = 11))

        val result = checker(installedVersionCode = 10).check()

        assertTrue("expected UpdateAvailable, got $result", result is VersionCheckResult.UpdateAvailable)
        val config = (result as VersionCheckResult.UpdateAvailable).config
        assertEquals(11, config.versionCode)
        assertEquals("https://updates.example.com/app.apk", config.apkUrl)
        assertEquals("deadbeef", config.sha256)
    }

    // --- "No update" must stay distinguishable from "check failed" ---

    @Test
    fun `equal versionCode means no update, not an error`() = runBlocking {
        enqueue(body = manifest(versionCode = 10))

        assertEquals(VersionCheckResult.UpToDate, checker(installedVersionCode = 10).check())
    }

    @Test
    fun `older versionCode means no update`() = runBlocking {
        enqueue(body = manifest(versionCode = 9))

        assertEquals(VersionCheckResult.UpToDate, checker(installedVersionCode = 10).check())
    }

    @Test
    fun `malformed json is an error, not no-update`() = runBlocking {
        enqueue(body = "{\"versionCode\": ")

        val result = checker().check()

        assertTrue("expected Failed, got $result", result is VersionCheckResult.Failed)
        assertEquals(FailureKind.PERMANENT, (result as VersionCheckResult.Failed).kind)
    }

    @Test
    fun `json missing a required field is an error, not no-update`() = runBlocking {
        // Valid JSON, no apkUrl: the old code caught JSONException and returned
        // null, which the caller read as "you are up to date".
        enqueue(body = """{"versionCode": 11, "versionName": "1.1.0", "sha256": "abc"}""")

        val result = checker().check()

        assertTrue("expected Failed, got $result", result is VersionCheckResult.Failed)
        assertEquals(FailureKind.PERMANENT, (result as VersionCheckResult.Failed).kind)
    }

    @Test
    fun `empty body is an error, not no-update`() = runBlocking {
        enqueue(body = "")

        val result = checker().check()

        assertTrue("expected Failed, got $result", result is VersionCheckResult.Failed)
    }

    // --- Retry policy ---

    @Test
    fun `404 is permanent`() = runBlocking {
        enqueue(code = 404, body = "nope")

        val result = checker().check()

        assertEquals(FailureKind.PERMANENT, (result as VersionCheckResult.Failed).kind)
    }

    @Test
    fun `503 is transient`() = runBlocking {
        enqueue(code = 503, body = "later")

        val result = checker().check()

        assertEquals(FailureKind.TRANSIENT, (result as VersionCheckResult.Failed).kind)
    }

    @Test
    fun `429 is transient even though it is a 4xx`() = runBlocking {
        enqueue(code = 429, body = "slow down")

        val result = checker().check()

        assertEquals(FailureKind.TRANSIENT, (result as VersionCheckResult.Failed).kind)
    }

    @Test
    fun `unreachable server is transient`() = runBlocking {
        val dead = VersionChecker(
            endpoint = server.url("/t.json").toString(),
            currentVersionCode = { 10 },
            client = client,
            sdkInt = 34
        )
        server.close() // nothing is listening any more

        val result = dead.check()

        assertEquals(FailureKind.TRANSIENT, (result as VersionCheckResult.Failed).kind)
    }

    // --- Refusals ---

    @Test
    fun `plain http apkUrl is refused`() = runBlocking {
        enqueue(body = manifest(apkUrl = "http://updates.example.com/app.apk"))

        val result = checker().check()

        assertTrue("expected Failed, got $result", result is VersionCheckResult.Failed)
        assertEquals(FailureKind.PERMANENT, (result as VersionCheckResult.Failed).kind)
    }

    @Test
    fun `update needing a newer android than this device is not offered`() = runBlocking {
        enqueue(
            body = """
                {
                  "versionCode": 11,
                  "versionName": "1.1.0",
                  "apkUrl": "https://updates.example.com/app.apk",
                  "sha256": "deadbeef",
                  "minAndroidSdk": 35
                }
            """.trimIndent()
        )

        assertEquals(VersionCheckResult.UpToDate, checker(sdkInt = 34).check())
    }

    @Test
    fun `blank endpoint short-circuits without a request`() = runBlocking {
        val result = VersionChecker(
            endpoint = "",
            currentVersionCode = { 10 },
            client = client,
            sdkInt = 34
        ).check()

        assertEquals(VersionCheckResult.NotConfigured, result)
        assertEquals(0, server.requestCount)
    }

    // --- The nullable wrapper MainActivity still uses ---

    @Test
    fun `checkForUpdate returns null for a failed check`() = runBlocking {
        enqueue(code = 500, body = "boom")

        assertEquals(null, checker().checkForUpdate())
    }
}
