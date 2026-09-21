package com.tiredvpn.android.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    /** Every field, each at a value the defaults would not produce. */
    private fun everyField() = VpnConfig(
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
        shaperPreset = "youtube_streaming",
        shaperSeed = 7L,
        echEnabled = true,
        echConfig = "AEr+DQBG",
        echPublicName = "ech.example.net",
        serverAddressV6 = "[2001:db8::1]:995",
        preferIpv6 = true,
        fallbackV4 = false,
        tunnelIpv6 = "dual",
        serverSelectionPolicy = "latency",
        quicSniFrag = true,
        mtu = 1380,
        customDns = "9.9.9.9",
    )

    @Test
    fun `toJson and fromJson round-trip preserves all fields`() {
        val original = everyField()

        val restored = VpnConfig.fromJson(original.toJson())

        assertEquals(original, restored)
    }

    /**
     * The round-trip above only covers the fields it happens to name, and the
     * pair it exercises is the one the server store writes and reads: a field
     * added to the data class but missed in [VpnConfig.toJson] is silently
     * dropped on every restart, and the test still passes because it never
     * heard of that field either.
     *
     * So the field list is taken from the class rather than retyped. Java
     * reflection, not Kotlin's - kotlin-reflect is not on the test classpath,
     * and a data class gives every constructor property a backing field with
     * the property's name.
     */
    @Test
    fun `every field of the data class is written to JSON and set above`() {
        val declared = VpnConfig::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        // Positive control (rule 2): reflection that found nothing would pass
        // every assertion below.
        assertTrue("reflection found no fields at all", declared.size > 20)
        assertTrue(declared.containsAll(listOf("serverAddress", "secret", "customDns")))

        val written = everyField().toJson().keys().asSequence().toSet()
        assertEquals("fields the JSON does not carry", emptySet<String>(), declared - written)
        assertEquals("JSON keys no field answers to", emptySet<String>(), written - declared)

        // And the sample really does move every one of them off its default,
        // or the round-trip test proves nothing about the ones it skipped. The
        // three identifying fields have no default to differ from.
        val sample = everyField()
        val defaults = VpnConfig(serverAddress = "1.2.3.4", serverPort = 443, secret = "secretXYZ")
        val untouched = declared.filterNot { name ->
            val field = VpnConfig::class.java.getDeclaredField(name).apply { isAccessible = true }
            field.get(sample) != field.get(defaults)
        }
        assertEquals(
            "left at its default value, so the round-trip says nothing about it",
            listOf("secret", "serverAddress", "serverPort"),
            untouched.sorted(),
        )
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
        assertEquals(ServerPoolConfig.DEFAULT_POLICY, config.serverSelectionPolicy)
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

    // --- tunnelIpv6 (dual-stack policy) ---

    @Test
    fun `tunnelIpv6 defaults to off`() {
        assertEquals("off", validConfig().tunnelIpv6)

        val json = JSONObject().apply {
            put("serverAddress", "10.0.0.1")
            put("serverPort", 993)
            put("secret", "s")
        }
        // Old persisted configs lack the field - must stay off (old app + new core)
        assertEquals("off", VpnConfig.fromJson(json).tunnelIpv6)
    }

    @Test
    fun `tunnelIpv6 survives the JSON round-trip`() {
        val dual = validConfig().copy(tunnelIpv6 = "dual")
        assertEquals("dual", VpnConfig.fromJson(dual.toJson()).tunnelIpv6)

        val off = validConfig().copy(tunnelIpv6 = "off")
        assertEquals("off", VpnConfig.fromJson(off.toJson()).tunnelIpv6)
    }

    @Test
    fun `tunnelIpv6 survives the URL round-trip only when non-default`() {
        val dual = validConfig().copy(tunnelIpv6 = "dual")
        assertEquals("dual", VpnConfig.fromUrl(dual.toUrl())!!.tunnelIpv6)

        // Default off is omitted from the URL to keep it short
        val off = validConfig()
        assertFalse(off.toUrl().contains("tunIpv6"))
        assertEquals("off", VpnConfig.fromUrl(off.toUrl())!!.tunnelIpv6)
    }
}
