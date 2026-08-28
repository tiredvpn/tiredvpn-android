package com.tiredvpn.android.importer

import android.util.Base64
import com.tiredvpn.android.vpn.VpnConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns whatever the user pasted, tapped or pushed over adb into servers.
 *
 * Before this existed the app had four separate parsers - one in the ADB
 * receiver, one on the config screen, one on the server list, one in settings -
 * and they understood four different field sets. A config exported by the app
 * could not always be read back by the app.
 *
 * The caller never picks a format. [parse] sniffs the payload:
 *
 *   tired://a:995?secret=x                 one link
 *   tired://a:995?...\ntired://b:995?...   several links, one per line
 *   ...anything... tired://a:995?... ...   a link inside a chat message
 *   {"server":"a","port":995,...}          one server as JSON
 *   [{...},{...}]                          several servers as JSON
 *   {"servers":[{...},{...}]}              an export bundle
 *   dGlyZWQ6Ly9hOjk5NT9zZWNyZXQ9eA==       base64 of any of the above
 *
 * Field names are accepted in both spellings everywhere: the snake_case used by
 * the ADB documentation ("server_v6"), the camelCase written by
 * [VpnConfig.toJson] ("serverAddressV6"), and the query-parameter name used by
 * [VpnConfig.toUrl] ("serverV6"). One vocabulary, three surfaces.
 */
object ConfigCodec {

    /** How a payload was recognised. Exposed for tests and for error messages. */
    enum class Format { LINKS, JSON_ARRAY, JSON_OBJECT, BASE64, UNKNOWN }

    /** Nested base64 is legal once (a subscription blob); twice is someone playing. */
    private const val MAX_BASE64_DEPTH = 2

    /**
     * A server as it arrived, before it is matched against what is already stored.
     * Split-tunnel rules travel with the server they were exported next to.
     */
    data class ParsedServer(
        val config: VpnConfig,
        val splitTunnel: SplitTunnelSpec? = null,
    )

    data class SplitTunnelSpec(val mode: String, val apps: Set<String>)

    /**
     * Something the user asked to import that did not become a server.
     *
     * [label] is shown in the UI, so it must never carry a secret: it is built
     * from the endpoint or from the entry's position, never from the raw text.
     */
    data class Skipped(val label: String, val reason: String)

    data class ParseResult(
        val format: Format,
        val servers: List<ParsedServer>,
        val skipped: List<Skipped>,
    ) {
        val isEmpty: Boolean get() = servers.isEmpty() && skipped.isEmpty()

        companion object {
            fun nothing(format: Format = Format.UNKNOWN) =
                ParseResult(format, emptyList(), emptyList())
        }
    }

    const val REASON_MALFORMED_LINK = "not a usable tired:// link"
    const val REASON_INCOMPLETE = "server address, port or secret missing"
    const val REASON_NOT_AN_OBJECT = "not a server object"

    // --- entry point ---

    fun parse(raw: String?): ParseResult = parseAt(raw, depth = 0)

    /**
     * Recognise the payload without parsing it. Kept separate so the decision and
     * the work can be tested apart, and so error messages can name the format.
     */
    fun detect(raw: String?): Format {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Format.UNKNOWN
        // A link wins over everything: JSON that merely quotes a tired:// link is
        // still a set of links as far as the user is concerned.
        if (text.contains("tired://", ignoreCase = true)) return Format.LINKS
        if (text.startsWith("[")) return Format.JSON_ARRAY
        if (text.startsWith("{")) return Format.JSON_OBJECT
        if (looksBase64(text)) return Format.BASE64
        return Format.UNKNOWN
    }

    private fun parseAt(raw: String?, depth: Int): ParseResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ParseResult.nothing()

        return when (val format = detect(text)) {
            Format.LINKS -> parseLinks(text)
            Format.JSON_ARRAY -> parseJsonArray(text, depth)
            Format.JSON_OBJECT -> parseJsonObject(text, depth)
            Format.BASE64 -> {
                if (depth >= MAX_BASE64_DEPTH) return ParseResult.nothing(format)
                val decoded = decodeBase64(text) ?: return ParseResult.nothing(format)
                val inner = parseAt(decoded, depth + 1)
                // Report the format the user actually handed us, not the inner one:
                // "base64 that decoded to nothing usable" is the useful message.
                if (inner.isEmpty) ParseResult.nothing(format) else inner
            }
            Format.UNKNOWN -> ParseResult.nothing(format)
        }
    }

    // --- links ---

    private fun parseLinks(text: String): ParseResult {
        val links = VpnConfig.extractTiredUrls(text)
        val servers = mutableListOf<ParsedServer>()
        val skipped = mutableListOf<Skipped>()

        links.forEachIndexed { index, link ->
            val config = VpnConfig.fromUrl(link)
            when {
                config == null -> skipped += Skipped(linkLabel(link, index), REASON_MALFORMED_LINK)
                !config.isValid -> skipped += Skipped(linkLabel(link, index), REASON_INCOMPLETE)
                else -> servers += ParsedServer(config)
            }
        }
        return ParseResult(Format.LINKS, servers, skipped)
    }

    /** Endpoint only. The secret lives in the query string, which is dropped here. */
    private fun linkLabel(link: String, index: Int): String {
        val authority = link.substringAfter("://", "")
            .substringBefore('?')
            .substringBefore('/')
            .substringAfterLast('@')
        return authority.ifBlank { "link #${index + 1}" }
    }

    // --- JSON ---

    private fun parseJsonArray(text: String, depth: Int): ParseResult {
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            return ParseResult.nothing(Format.JSON_ARRAY)
        }
        val result = parseArrayElements(array, depth)
        return ParseResult(Format.JSON_ARRAY, result.first, result.second)
    }

    private fun parseJsonObject(text: String, depth: Int): ParseResult {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return ParseResult.nothing(Format.JSON_OBJECT)
        }

        // An export bundle: {"servers":[...]} — also accepted under "configs".
        for (key in BUNDLE_KEYS) {
            val nested = obj.optJSONArray(key) ?: continue
            val result = parseArrayElements(nested, depth)
            return ParseResult(Format.JSON_OBJECT, result.first, result.second)
        }

        val parsed = serverFromJson(obj)
        return if (parsed == null) {
            ParseResult(
                Format.JSON_OBJECT,
                emptyList(),
                listOf(Skipped(objectLabel(obj, 0), REASON_INCOMPLETE)),
            )
        } else {
            ParseResult(Format.JSON_OBJECT, listOf(parsed), emptyList())
        }
    }

    private fun parseArrayElements(
        array: JSONArray,
        depth: Int,
    ): Pair<List<ParsedServer>, List<Skipped>> {
        val servers = mutableListOf<ParsedServer>()
        val skipped = mutableListOf<Skipped>()

        for (i in 0 until array.length()) {
            when (val element = array.opt(i)) {
                is JSONObject -> {
                    val parsed = serverFromJson(element)
                    if (parsed == null) {
                        skipped += Skipped(objectLabel(element, i), REASON_INCOMPLETE)
                    } else {
                        servers += parsed
                    }
                }
                // An array of links, or of base64 blobs, is a legal shape too.
                is String -> {
                    val inner = parseAt(element, depth + 1)
                    if (inner.servers.isEmpty() && inner.skipped.isEmpty()) {
                        skipped += Skipped("entry #${i + 1}", REASON_MALFORMED_LINK)
                    } else {
                        servers += inner.servers
                        skipped += inner.skipped
                    }
                }
                else -> skipped += Skipped("entry #${i + 1}", REASON_NOT_AN_OBJECT)
            }
        }
        return servers to skipped
    }

    private val BUNDLE_KEYS = listOf("servers", "configs")

    private fun objectLabel(obj: JSONObject, index: Int): String {
        val host = obj.firstString(SERVER_KEYS)
        val port = obj.firstInt(PORT_KEYS)
        return when {
            !host.isNullOrBlank() && port != null -> "$host:$port"
            !host.isNullOrBlank() -> host
            else -> obj.firstString(listOf("name")) ?: "entry #${index + 1}"
        }
    }

    // Field name lists. Order matters only when a payload spells the same field
    // twice, which no exporter does; the first present key wins.
    private val SERVER_KEYS = listOf("server", "serverAddress", "server_address", "address", "host")
    private val PORT_KEYS = listOf("port", "serverPort", "server_port")

    /**
     * Build a server from one JSON object, accepting every spelling of every
     * field. Returns null when the result would not be connectable.
     */
    fun serverFromJson(json: JSONObject): ParsedServer? {
        val config = VpnConfig(
            id = json.firstString(listOf("id"))?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString(),
            name = json.firstString(listOf("name"))?.takeIf { it.isNotBlank() } ?: "Server",
            serverAddress = json.firstString(SERVER_KEYS).orEmpty().trim(),
            serverPort = json.firstInt(PORT_KEYS) ?: 993,
            secret = json.firstString(listOf("secret")).orEmpty(),
            strategy = json.firstString(listOf("strategy")) ?: "auto",
            enableQuic = json.firstBool(listOf("quic", "enableQuic", "enable_quic")) ?: true,
            quicPort = json.firstInt(listOf("quic_port", "quicPort")) ?: 443,
            coverHost = json.firstString(listOf("cover_host", "coverHost", "cover"))
                ?: "api.googleapis.com",
            rttMasking = json.firstBool(listOf("rtt_masking", "rttMasking", "rtt")) ?: false,
            rttProfile = json.firstString(listOf("rtt_profile", "rttProfile")) ?: "moscow-yandex",
            fallbackEnabled = json.firstBool(listOf("fallback", "fallbackEnabled")) ?: true,
            debugLogging = json.firstBool(listOf("debug", "debugLogging", "debug_logging")) ?: false,
            lastLatencyMs = json.firstLong(listOf("lastLatencyMs", "last_latency_ms")) ?: -1L,
            connectionMode = json.firstString(listOf("mode", "connectionMode", "connection_mode"))
                ?: "tun",
            proxyPort = json.firstInt(listOf("proxy_port", "proxyPort")) ?: 8080,
            // Port hopping
            portHoppingEnabled = json.firstBool(
                listOf("hop", "port_hopping", "portHoppingEnabled")
            ) ?: false,
            portHopRangeStart = json.firstInt(listOf("hopStart", "hop_start", "portHopRangeStart"))
                ?: 47000,
            portHopRangeEnd = json.firstInt(listOf("hopEnd", "hop_end", "portHopRangeEnd"))
                ?: 65535,
            portHopIntervalMs = json.firstLong(
                listOf("hopInterval", "hop_interval", "portHopIntervalMs")
            ) ?: 60_000L,
            portHopStrategy = json.firstString(
                listOf("hopStrategy", "hop_strategy", "portHopStrategy")
            ) ?: "random",
            portHopSeed = json.firstString(listOf("hopSeed", "hop_seed", "portHopSeed"))
                ?.takeIf { it.isNotEmpty() },
            // Traffic shaper
            shaperPreset = json.firstString(listOf("shaper", "shaper_preset", "shaperPreset")) ?: "",
            shaperSeed = json.firstLong(listOf("shaperSeed", "shaper_seed")) ?: 0L,
            // ECH
            echEnabled = json.firstBool(listOf("ech", "ech_enabled", "echEnabled")) ?: false,
            echConfig = json.firstString(listOf("echConfig", "ech_config")) ?: "",
            echPublicName = json.firstString(listOf("echPublicName", "ech_public_name"))
                ?: "cloudflare-ech.com",
            // IPv6 endpoint
            serverAddressV6 = json.firstString(
                listOf("server_v6", "serverV6", "serverAddressV6", "server_address_v6")
            ) ?: "",
            preferIpv6 = json.firstBool(listOf("prefer_ipv6", "preferIpv6")) ?: false,
            fallbackV4 = json.firstBool(listOf("fallback_v4", "fallbackV4")) ?: true,
            // IPv6 inside the tunnel
            tunnelIpv6 = json.firstString(listOf("tun_ipv6", "tunIpv6", "tunnelIpv6")) ?: "off",
            quicSniFrag = json.firstBool(listOf("quicSniFrag", "quic_sni_frag")) ?: false,
            // Tunnel overrides
            mtu = json.firstInt(listOf("mtu")) ?: 0,
            customDns = json.firstString(listOf("dns", "customDns", "custom_dns")) ?: "",
        )
        if (!config.isValid) return null

        return ParsedServer(config, splitTunnelFromJson(json))
    }

    private fun splitTunnelFromJson(json: JSONObject): SplitTunnelSpec? {
        val split = json.optJSONObject("split_tunneling")
            ?: json.optJSONObject("splitTunneling")
            ?: return null
        val mode = split.firstString(listOf("mode")) ?: "exclude"
        val appsArray = split.optJSONArray("apps")
        val apps = buildSet {
            for (i in 0 until (appsArray?.length() ?: 0)) {
                appsArray?.optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
        return SplitTunnelSpec(mode, apps)
    }

    // --- base64 ---

    private val BASE64_CHARS = Regex("^[A-Za-z0-9+/\\-_=]+$")

    private fun looksBase64(text: String): Boolean {
        val compact = text.filterNot { it.isWhitespace() }
        // Short strings are far more likely to be a typo than a subscription blob.
        if (compact.length < 16) return false
        return BASE64_CHARS.matches(compact)
    }

    /**
     * Decode a subscription-style blob. Accepts standard and URL-safe alphabets
     * with or without padding, and refuses anything that decodes to bytes no
     * human pasted - otherwise random text would "decode" into garbage and the
     * user would get a parse error about the garbage instead of about their input.
     */
    private fun decodeBase64(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
            .replace('-', '+')
            .replace('_', '/')
            .trimEnd('=')
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val bytes = try {
            Base64.decode(padded, Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }
        if (bytes.isEmpty()) return null
        val printable = bytes.all { b ->
            val v = b.toInt() and 0xFF
            v in 0x20..0x7E || v == 0x09 || v == 0x0A || v == 0x0D || v >= 0x80
        }
        if (!printable) return null
        return String(bytes, Charsets.UTF_8)
    }
}

// --- JSON field readers: first present key wins, absent means "use the default" ---

private fun JSONObject.present(key: String): Boolean = has(key) && !isNull(key)

private fun JSONObject.firstString(keys: List<String>): String? =
    keys.firstOrNull { present(it) }?.let { optString(it) }

private fun JSONObject.firstInt(keys: List<String>): Int? =
    keys.firstOrNull { present(it) }?.let { key ->
        // A port arriving as "995" from a hand-written config must still work.
        opt(key).let { value ->
            (value as? Number)?.toInt() ?: value?.toString()?.trim()?.toIntOrNull()
        }
    }

private fun JSONObject.firstLong(keys: List<String>): Long? =
    keys.firstOrNull { present(it) }?.let { key ->
        opt(key).let { value ->
            (value as? Number)?.toLong() ?: value?.toString()?.trim()?.toLongOrNull()
        }
    }

private fun JSONObject.firstBool(keys: List<String>): Boolean? =
    keys.firstOrNull { present(it) }?.let { key ->
        when (val value = opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value?.toString()?.trim()?.lowercase()?.toBooleanStrictOrNull()
        }
    }
