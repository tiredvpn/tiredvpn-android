package com.tiredvpn.android.receiver

import android.content.Intent
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Asserts on the config that ends up STORED, not on a parse helper.
 *
 * The receiver reads its JSON and builds a `VpnConfig` in one expression, so a
 * field can be read correctly and still never reach the constructor - and a test
 * that called a parser directly would stay green through exactly that mistake.
 * Every assertion here therefore goes through `onReceive` and reads the value
 * back out of `ServerRepository`, which is the same path an `adb` broadcast
 * takes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigImportReceiverTest {

    private fun importJson(json: String): VpnConfig? {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(ConfigImportReceiver.ACTION_IMPORT_CONFIG).putExtra("json", json)
        ConfigImportReceiver().onReceive(context, intent)
        return ServerRepository.getActiveServer(context)
    }

    private val minimalV4 = """"server":"198.51.100.1","port":995,"secret":"s3cr3t""""

    @Test
    fun `the import path itself works - positive control`() {
        // Rule 2: every "field X survived" assertion below is worthless if the
        // receiver silently imports nothing. This is the control that says it does.
        val saved = importJson("{$minimalV4,\"name\":\"AMS\"}")

        assertNotNull("nothing was imported at all", saved)
        assertEquals("AMS", saved!!.name)
        assertEquals("198.51.100.1", saved.serverAddress)
        assertEquals(995, saved.serverPort)
        assertEquals("s3cr3t", saved.secret)
    }

    // --- IPv6 endpoint ---

    @Test
    fun `snake_case v6 fields reach the stored config`() {
        // Non-default values on purpose: fallbackV4 defaults to true and
        // tunnelIpv6 to "off", so an implementation that hardcodes the defaults
        // would pass a test written with them.
        val saved = importJson(
            """{$minimalV4,
                "server_v6":"[2001:db8::1]:995",
                "prefer_ipv6":true,
                "fallback_v4":false,
                "tun_ipv6":"dual"}"""
        )

        assertNotNull(saved)
        assertEquals("[2001:db8::1]:995", saved!!.serverAddressV6)
        assertTrue("prefer_ipv6 did not reach the stored config", saved.preferIpv6)
        assertEquals(false, saved.fallbackV4)
        assertEquals("dual", saved.tunnelIpv6)
    }

    @Test
    fun `camelCase v6 fields are accepted, so an exported config can be re-imported`() {
        val saved = importJson(
            """{$minimalV4,
                "serverAddressV6":"v6.example.com:995",
                "preferIpv6":true,
                "fallbackV4":false,
                "tunnelIpv6":"dual"}"""
        )

        assertNotNull(saved)
        assertEquals("v6.example.com:995", saved!!.serverAddressV6)
        assertTrue(saved.preferIpv6)
        assertEquals(false, saved.fallbackV4)
        assertEquals("dual", saved.tunnelIpv6)
    }

    @Test
    fun `a hostname v6 endpoint is stored verbatim, brackets are not required`() {
        // The model stores host:port as text and splits it later; the receiver
        // must not "helpfully" normalise or reject anything here.
        val saved = importJson("""{$minimalV4,"server_v6":"ams.example.org:993"}""")

        assertEquals("ams.example.org:993", saved!!.serverAddressV6)
    }

    @Test
    fun `an explicitly empty v6 address is a legal value, not an error`() {
        val saved = importJson("""{$minimalV4,"server_v6":""}""")

        assertNotNull("an empty server_v6 must not abort the import", saved)
        assertEquals("", saved!!.serverAddressV6)
    }

    // --- backward compatibility ---

    @Test
    fun `a config with no v6 fields is imported exactly as before`() {
        // The full pre-existing field set, values chosen to differ from every
        // default so a dropped assignment shows up.
        val saved = importJson(
            """{"name":"Legacy","server":"203.0.113.7","port":8443,"secret":"old-secret",
                "strategy":"reality","quic":false,"quic_port":8444,
                "cover_host":"www.example.com","rtt_masking":true,
                "rtt_profile":"siberia","fallback":false,"debug":true}"""
        )

        assertNotNull(saved)
        assertEquals("Legacy", saved!!.name)
        assertEquals("203.0.113.7", saved.serverAddress)
        assertEquals(8443, saved.serverPort)
        assertEquals("old-secret", saved.secret)
        assertEquals("reality", saved.strategy)
        assertEquals(false, saved.enableQuic)
        assertEquals(8444, saved.quicPort)
        assertEquals("www.example.com", saved.coverHost)
        assertTrue(saved.rttMasking)
        assertEquals("siberia", saved.rttProfile)
        assertEquals(false, saved.fallbackEnabled)
        assertTrue(saved.debugLogging)
    }

    @Test
    fun `absent v6 fields fall back to the model defaults, not to something invented`() {
        val saved = importJson("{$minimalV4}")!!
        val defaults = VpnConfig(serverAddress = "x", serverPort = 1, secret = "y")

        assertEquals(defaults.serverAddressV6, saved.serverAddressV6)
        assertEquals(defaults.preferIpv6, saved.preferIpv6)
        assertEquals(defaults.fallbackV4, saved.fallbackV4)
        assertEquals(defaults.tunnelIpv6, saved.tunnelIpv6)
    }
}
