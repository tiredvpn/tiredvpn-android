package com.tiredvpn.android.importer

import android.util.Base64
import com.tiredvpn.android.vpn.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Format sniffing and parsing.
 *
 * These tests stop at the codec on purpose; whether the screens hand it the
 * right string is a separate question, answered by ImportCallSiteTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigCodecTest {

    private fun link(host: String, port: Int = 995, secret: String = "s3cr3t", extra: String = "") =
        "tired://$host:$port?secret=$secret$extra"

    private fun b64(text: String): String =
        Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)

    // --- one link ---

    @Test
    fun `a single link becomes a single server`() {
        val parsed = ConfigCodec.parse(link("198.51.100.1"))

        assertEquals(ConfigCodec.Format.LINKS, parsed.format)
        assertEquals(1, parsed.servers.size)
        assertEquals("198.51.100.1", parsed.servers[0].config.serverAddress)
        assertEquals(995, parsed.servers[0].config.serverPort)
        assertEquals("s3cr3t", parsed.servers[0].config.secret)
    }

    @Test
    fun `a link wrapped in a chat message is still found`() {
        val parsed = ConfigCodec.parse("вот конфиг: ${link("198.51.100.1")} подключайся")

        assertEquals(1, parsed.servers.size)
        assertEquals("198.51.100.1", parsed.servers[0].config.serverAddress)
    }

    // --- several links: the case that made this work necessary ---

    @Test
    fun `four links produce four servers, not the first one`() {
        val payload = listOf("a.example", "b.example", "c.example", "d.example")
            .joinToString("\n") { link(it) }

        val parsed = ConfigCodec.parse(payload)

        assertEquals(4, parsed.servers.size)
        assertEquals(
            listOf("a.example", "b.example", "c.example", "d.example"),
            parsed.servers.map { it.config.serverAddress },
        )
    }

    @Test
    fun `every link keeps its own secret - the pool has one key per node`() {
        val payload = (1..3).joinToString("\n") { link("n$it.example", secret = "key-$it") }

        val parsed = ConfigCodec.parse(payload)

        assertEquals(listOf("key-1", "key-2", "key-3"), parsed.servers.map { it.config.secret })
    }

    @Test
    fun `several links on one line, space separated, still split`() {
        val parsed = ConfigCodec.parse("${link("a.example")} ${link("b.example")}")

        assertEquals(2, parsed.servers.size)
    }

    @Test
    fun `a broken link among good ones is skipped, the good ones survive`() {
        val payload = listOf(
            link("a.example"),
            "tired://b.example:995",            // no secret
            link("c.example"),
        ).joinToString("\n")

        val parsed = ConfigCodec.parse(payload)

        assertEquals(listOf("a.example", "c.example"), parsed.servers.map { it.config.serverAddress })
        assertEquals(1, parsed.skipped.size)
        assertEquals("b.example:995", parsed.skipped[0].label)
    }

    @Test
    fun `a skip label never carries the secret`() {
        // The label is rendered in a dialog and, for the receiver, logged.
        val parsed = ConfigCodec.parse("tired://b.example:995?nosecret=hunter2")

        assertEquals(1, parsed.skipped.size)
        assertFalse(parsed.skipped[0].label.contains("hunter2"))
        assertFalse(parsed.skipped[0].label.contains("?"))
    }

    // --- JSON ---

    @Test
    fun `a snake_case JSON object becomes one server`() {
        val parsed = ConfigCodec.parse(
            """{"name":"AMS","server":"198.51.100.1","port":995,"secret":"s3cr3t",
                "server_v6":"[2001:db8::1]:995","prefer_ipv6":true,"tun_ipv6":"dual"}"""
        )

        assertEquals(ConfigCodec.Format.JSON_OBJECT, parsed.format)
        val config = parsed.servers.single().config
        assertEquals("AMS", config.name)
        assertEquals("[2001:db8::1]:995", config.serverAddressV6)
        assertTrue(config.preferIpv6)
        assertEquals("dual", config.tunnelIpv6)
    }

    @Test
    fun `a JSON array of three becomes three servers`() {
        val parsed = ConfigCodec.parse(
            """[{"server":"a.example","port":995,"secret":"k1"},
                {"server":"b.example","port":996,"secret":"k2"},
                {"server":"c.example","port":997,"secret":"k3"}]"""
        )

        assertEquals(ConfigCodec.Format.JSON_ARRAY, parsed.format)
        assertEquals(3, parsed.servers.size)
        assertEquals(listOf(995, 996, 997), parsed.servers.map { it.config.serverPort })
    }

    @Test
    fun `an export bundle with a servers array is unwrapped`() {
        val parsed = ConfigCodec.parse(
            """{"version":2,"servers":[{"server":"a.example","port":995,"secret":"k1"},
                {"server":"b.example","port":995,"secret":"k2"}]}"""
        )

        assertEquals(2, parsed.servers.size)
    }

    @Test
    fun `an array of links is accepted too`() {
        val parsed = ConfigCodec.parse("""["${link("a.example")}","${link("b.example")}"]""")

        assertEquals(2, parsed.servers.size)
    }

    @Test
    fun `an IPv6 literal link survives the extractor`() {
        // The link ends at whitespace and at structural characters, but NOT at
        // square brackets: an IPv6 authority is written [2001:db8::1]:995.
        // Brackets are kept in serverAddress, which is what makes serverEndpoint
        // ("[2001:db8::1]:995") dialable.
        val parsed = ConfigCodec.parse("use tired://[2001:db8::1]:995?secret=k please")

        assertEquals("[2001:db8::1]", parsed.servers.single().config.serverAddress)
        assertEquals(995, parsed.servers.single().config.serverPort)
    }

    @Test
    fun `a JSON object missing the secret is skipped, not stored half-built`() {
        val parsed = ConfigCodec.parse("""{"server":"a.example","port":995}""")

        assertTrue(parsed.servers.isEmpty())
        assertEquals("a.example:995", parsed.skipped.single().label)
    }

    @Test
    fun `a port given as a string still parses`() {
        // Hand-written configs and shell here-docs quote everything.
        val parsed = ConfigCodec.parse("""{"server":"a.example","port":"995","secret":"k"}""")

        assertEquals(995, parsed.servers.single().config.serverPort)
    }

    @Test
    fun `split tunneling travels with the server it was exported beside`() {
        val parsed = ConfigCodec.parse(
            """{"server":"a.example","port":995,"secret":"k",
                "split_tunneling":{"mode":"include","apps":["com.foo","com.bar"]}}"""
        )

        val split = parsed.servers.single().splitTunnel
        assertNotNull(split)
        assertEquals("include", split!!.mode)
        assertEquals(setOf("com.foo", "com.bar"), split.apps)
    }

    // --- base64 ---

    @Test
    fun `base64 of a link list decodes and parses`() {
        val payload = b64((1..4).joinToString("\n") { link("n$it.example") })

        val parsed = ConfigCodec.parse(payload)

        assertEquals(4, parsed.servers.size)
    }

    @Test
    fun `base64 of a JSON array decodes and parses`() {
        val payload = b64("""[{"server":"a.example","port":995,"secret":"k1"}]""")

        assertEquals(1, ConfigCodec.parse(payload).servers.size)
    }

    @Test
    fun `url-safe base64 without padding decodes`() {
        val raw = (1..2).joinToString("\n") { link("n$it.example") }
        val payload = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
            .trimEnd('=')

        assertEquals(2, ConfigCodec.parse(payload).servers.size)
    }

    @Test
    fun `base64 wrapped across lines decodes`() {
        val raw = (1..4).joinToString("\n") { link("n$it.example") }
        val payload = Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP).chunked(40).joinToString("\n")

        assertEquals(4, ConfigCodec.parse(payload).servers.size)
    }

    // --- nothing usable ---

    @Test
    fun `plain prose yields nothing and says so`() {
        val parsed = ConfigCodec.parse("hello, this is not a config at all")

        assertEquals(ConfigCodec.Format.UNKNOWN, parsed.format)
        assertTrue(parsed.servers.isEmpty())
        assertTrue(parsed.skipped.isEmpty())
    }

    @Test
    fun `empty and null input are handled`() {
        assertTrue(ConfigCodec.parse(null).servers.isEmpty())
        assertTrue(ConfigCodec.parse("   \n ").servers.isEmpty())
    }

    @Test
    fun `a base64-looking word that decodes to noise is not reported as a config`() {
        // "detect" says BASE64 on shape alone; the decode step has to reject it,
        // otherwise the user gets an error about garbage instead of their input.
        val parsed = ConfigCodec.parse("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5")

        assertTrue(parsed.servers.isEmpty())
    }

    @Test
    fun `truncated JSON is not silently treated as success`() {
        val parsed = ConfigCodec.parse("""{"server":"a.example","port":995,"secret":""")

        assertTrue(parsed.servers.isEmpty())
    }

    // --- one vocabulary across link, JSON and receiver ---

    /** Every field a sender can set, all at a non-default value. */
    private val everything = VpnConfig(
        name = "Named By User",
        serverAddress = "ams.example.org",
        serverPort = 995,
        secret = "s3cr3t",
        strategy = "reality",
        enableQuic = false,
        quicPort = 8444,
        coverHost = "www.example.com",
        rttMasking = true,
        rttProfile = "siberia",
        fallbackEnabled = false,
        debugLogging = true,
        connectionMode = "proxy",
        proxyPort = 9090,
        portHoppingEnabled = true,
        portHopRangeStart = 40000,
        portHopRangeEnd = 41000,
        portHopIntervalMs = 15_000L,
        portHopStrategy = "fibonacci",
        portHopSeed = "deadbeef",
        shaperPreset = "youtube_streaming",
        shaperSeed = 42L,
        echEnabled = true,
        echConfig = "AEr+DQBG",
        echPublicName = "ech.example.net",
        serverAddressV6 = "[2001:db8::1]:995",
        preferIpv6 = true,
        fallbackV4 = false,
        tunnelIpv6 = "dual",
        quicSniFrag = true,
        mtu = 1380,
        customDns = "9.9.9.9",
    )

    @Test
    fun `a link carries every field a JSON config carries`() {
        // The regression this guards: the link parser knew about serverV6 and ECH
        // while the JSON path did not, so the same server imported differently
        // depending on which way it was sent.
        val parsed = ConfigCodec.parse(everything.toUrl())

        val roundTripped = parsed.servers.single().config
        assertEquals(
            everything.copy(id = roundTripped.id, lastLatencyMs = roundTripped.lastLatencyMs),
            roundTripped,
        )
    }

    @Test
    fun `a config exported as JSON re-imports unchanged`() {
        val parsed = ConfigCodec.parse(everything.toJson().toString())

        assertEquals(everything, parsed.servers.single().config)
    }

    @Test
    fun `link and JSON of the same server produce the same server`() {
        val fromLink = ConfigCodec.parse(everything.toUrl()).servers.single().config
        val fromJson = ConfigCodec.parse(everything.toJson().toString()).servers.single().config

        assertEquals(
            fromJson.copy(id = fromLink.id, lastLatencyMs = fromLink.lastLatencyMs),
            fromLink,
        )
    }

    @Test
    fun `snake_case spellings reach the same fields as camelCase`() {
        val snake = ConfigCodec.parse(
            """{"server":"a.example","port":995,"secret":"k","quic":false,"quic_port":8444,
                "cover_host":"www.example.com","rtt_masking":true,"rtt_profile":"siberia",
                "fallback":false,"debug":true,"server_v6":"[2001:db8::1]:995",
                "prefer_ipv6":true,"fallback_v4":false,"tun_ipv6":"dual",
                "ech":true,"ech_config":"AEr+DQBG","shaper":"youtube_streaming",
                "hop":true,"hop_start":40000,"quic_sni_frag":true,"dns":"9.9.9.9"}"""
        ).servers.single().config

        val camel = ConfigCodec.parse(
            """{"serverAddress":"a.example","serverPort":995,"secret":"k","enableQuic":false,
                "quicPort":8444,"coverHost":"www.example.com","rttMasking":true,
                "rttProfile":"siberia","fallbackEnabled":false,"debugLogging":true,
                "serverAddressV6":"[2001:db8::1]:995","preferIpv6":true,"fallbackV4":false,
                "tunnelIpv6":"dual","echEnabled":true,"echConfig":"AEr+DQBG",
                "shaperPreset":"youtube_streaming","portHoppingEnabled":true,
                "portHopRangeStart":40000,"quicSniFrag":true,"customDns":"9.9.9.9"}"""
        ).servers.single().config

        assertEquals(camel.copy(id = snake.id), snake)
    }

    @Test
    fun `absent fields fall back to the model defaults, not to something invented`() {
        val parsed = ConfigCodec.parse("""{"server":"a.example","port":995,"secret":"k"}""")
            .servers.single().config
        val defaults = VpnConfig(serverAddress = "a.example", serverPort = 995, secret = "k")

        assertEquals(defaults.copy(id = parsed.id, name = parsed.name), parsed)
    }

    // --- detect, separately from parse ---

    @Test
    fun `detect names the format it will use`() {
        assertEquals(ConfigCodec.Format.LINKS, ConfigCodec.detect(link("a.example")))
        assertEquals(ConfigCodec.Format.JSON_ARRAY, ConfigCodec.detect("[{}]"))
        assertEquals(ConfigCodec.Format.JSON_OBJECT, ConfigCodec.detect("{}"))
        assertEquals(ConfigCodec.Format.BASE64, ConfigCodec.detect(b64(link("a.example"))))
        assertEquals(ConfigCodec.Format.UNKNOWN, ConfigCodec.detect("just words here"))
        assertEquals(ConfigCodec.Format.UNKNOWN, ConfigCodec.detect(null))
    }

    @Test
    fun `extractTiredUrls returns every link in order`() {
        val urls = VpnConfig.extractTiredUrls("${link("a.example")}\n\n${link("b.example")}\n")

        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("a.example"))
        assertTrue(urls[1].contains("b.example"))
        assertNull(VpnConfig.extractTiredUrl("no link here"))
    }
}
