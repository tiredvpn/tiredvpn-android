package com.tiredvpn.android.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnConfigTest {

    private fun validConfig() = VpnConfig(
        serverAddress = "ss.example.com",
        serverPort = 995,
        secret = "dGVzdC1zZWNyZXQ="
    )

    // --- isValid ---

    @Test
    fun `isValid returns true for valid config`() {
        assertTrue(validConfig().isValid)
    }

    @Test
    fun `isValid returns false for blank address`() {
        assertFalse(validConfig().copy(serverAddress = "").isValid)
        assertFalse(validConfig().copy(serverAddress = "   ").isValid)
    }

    @Test
    fun `isValid returns false for blank secret`() {
        assertFalse(validConfig().copy(secret = "").isValid)
    }

    @Test
    fun `isValid returns false for port 0`() {
        assertFalse(validConfig().copy(serverPort = 0).isValid)
    }

    @Test
    fun `isValid returns false for port 65536`() {
        assertFalse(validConfig().copy(serverPort = 65536).isValid)
    }

    @Test
    fun `isValid returns true for port 65535`() {
        assertTrue(validConfig().copy(serverPort = 65535).isValid)
    }

    // --- serverEndpoint ---

    @Test
    fun `serverEndpoint formats correctly`() {
        assertEquals("ss.example.com:995", validConfig().serverEndpoint)
    }

    // --- JSON round-trip ---

    @Test
    fun `toJson and fromJson round-trip preserves all fields`() {
        val original = VpnConfig(
            id = "test-id-123",
            name = "My Server",
            serverAddress = "1.2.3.4",
            serverPort = 443,
            secret = "secretXYZ",
            strategy = "reality",
            enableQuic = false,
            quicPort = 8443,
            coverHost = "example.com",
            rttMasking = true,
            rttProfile = "siberia",
            fallbackEnabled = false,
            debugLogging = true,
            lastLatencyMs = 42,
            connectionMode = "proxy",
            proxyPort = 9090,
            portHoppingEnabled = true,
            portHopRangeStart = 48000,
            portHopRangeEnd = 60000,
            portHopIntervalMs = 30_000L,
            portHopStrategy = "fibonacci",
            portHopSeed = "abcdef12"
        )

        val restored = VpnConfig.fromJson(original.toJson())

        assertEquals(original, restored)
    }

    // --- fromJson defaults ---

    @Test
    fun `fromJson with minimal JSON uses defaults`() {
        val json = JSONObject().apply {
            put("serverAddress", "10.0.0.1")
            put("serverPort", 993)
            put("secret", "s3cr3t")
        }

        val config = VpnConfig.fromJson(json)

        assertEquals("auto", config.strategy)
        assertTrue(config.enableQuic)
        assertEquals(443, config.quicPort)
        assertEquals("api.googleapis.com", config.coverHost)
        assertFalse(config.rttMasking)
        assertEquals("moscow-yandex", config.rttProfile)
        assertTrue(config.fallbackEnabled)
        assertFalse(config.debugLogging)
        assertEquals(-1L, config.lastLatencyMs)
        assertEquals("tun", config.connectionMode)
        assertEquals(8080, config.proxyPort)
        assertFalse(config.portHoppingEnabled)
        assertEquals("random", config.portHopStrategy)
    }

    @Test
    fun `fromJson with empty portHopSeed returns null`() {
        val json = JSONObject().apply {
            put("serverAddress", "10.0.0.1")
            put("serverPort", 993)
            put("secret", "s")
            put("portHopSeed", "")
        }

        assertNull(VpnConfig.fromJson(json).portHopSeed)
    }

    @Test
    fun `fromJson with no id generates UUID of 36 chars`() {
        val json = JSONObject().apply {
            put("serverAddress", "10.0.0.1")
            put("serverPort", 993)
            put("secret", "s")
        }

        val config = VpnConfig.fromJson(json)
        assertEquals(36, config.id.length)
    }
}
