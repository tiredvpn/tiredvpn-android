package com.tiredvpn.android.update

import org.junit.Assert.*
import org.junit.Test

class UpdateConfigTest {

    private fun sampleConfig() = UpdateConfig(
        versionCode = 10,
        versionName = "1.0.0",
        apkUrl = "https://example.com/app.apk",
        sha256 = "abc123",
        releaseNotes = "Bug fixes"
    )

    // --- Default values ---

    @Test
    fun `default minAndroidSdk is 24`() {
        assertEquals(24, sampleConfig().minAndroidSdk)
    }

    @Test
    fun `default forceUpdate is false`() {
        assertFalse(sampleConfig().forceUpdate)
    }

    // --- All fields ---

    @Test
    fun `all fields set correctly`() {
        val config = UpdateConfig(
            versionCode = 20,
            versionName = "2.0.0",
            apkUrl = "https://cdn.example.com/v2.apk",
            sha256 = "deadbeef",
            releaseNotes = "New features",
            minAndroidSdk = 28,
            forceUpdate = true
        )

        assertEquals(20, config.versionCode)
        assertEquals("2.0.0", config.versionName)
        assertEquals("https://cdn.example.com/v2.apk", config.apkUrl)
        assertEquals("deadbeef", config.sha256)
        assertEquals("New features", config.releaseNotes)
        assertEquals(28, config.minAndroidSdk)
        assertTrue(config.forceUpdate)
    }

    // --- Data class equality ---

    @Test
    fun `data class equality works`() {
        val a = sampleConfig()
        val b = sampleConfig()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // --- Data class copy ---

    @Test
    fun `copy preserves unchanged fields`() {
        val original = sampleConfig()
        val copied = original.copy(versionCode = 99)

        assertEquals(99, copied.versionCode)
        assertEquals(original.versionName, copied.versionName)
        assertEquals(original.apkUrl, copied.apkUrl)
        assertEquals(original.sha256, copied.sha256)
        assertEquals(original.releaseNotes, copied.releaseNotes)
        assertEquals(original.minAndroidSdk, copied.minAndroidSdk)
        assertEquals(original.forceUpdate, copied.forceUpdate)
    }
}
