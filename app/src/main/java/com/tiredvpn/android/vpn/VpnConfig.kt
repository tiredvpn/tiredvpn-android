package com.tiredvpn.android.vpn

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.UUID

data class VpnConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Server",
    val serverAddress: String,
    val serverPort: Int,
    val secret: String,
    val strategy: String = "auto",
    val enableQuic: Boolean = true,
    val quicPort: Int = 443,
    val coverHost: String = "api.googleapis.com",
    val rttMasking: Boolean = false,
    val rttProfile: String = "moscow-yandex",
    val fallbackEnabled: Boolean = true,
    val debugLogging: Boolean = false,
    val lastLatencyMs: Long = -1, // -1 means unknown/checking
    val connectionMode: String = "tun", // "tun" or "proxy"
    val proxyPort: Int = 8080,
    // Port hopping settings
    val portHoppingEnabled: Boolean = false,
    val portHopRangeStart: Int = 47000,
    val portHopRangeEnd: Int = 65535,
    val portHopIntervalMs: Long = 60_000L,
    val portHopStrategy: String = "random", // random, sequential, fibonacci
    val portHopSeed: String? = null, // Optional seed for deterministic hopping (hex string)
    // Traffic shaper
    val shaperPreset: String = "", // "", "youtube_streaming", "chrome_browsing", "random_per_session"
    val shaperSeed: Long = 0L, // 0 = random
    // ECH (Encrypted Client Hello)
    val echEnabled: Boolean = false,
    val echConfig: String = "", // base64 ECHConfigList
    val echPublicName: String = "cloudflare-ech.com",
    // IPv6 endpoint
    val serverAddressV6: String = "", // "" = no IPv6 endpoint; format host:port or [v6]:port
    val preferIpv6: Boolean = false,
    val fallbackV4: Boolean = true,
    // IPv6 inside the tunnel: "off" (v4-only, leak blackhole) or "dual" (dual-stack)
    val tunnelIpv6: String = "off",
    // QUIC SNI fragmentation
    val quicSniFrag: Boolean = false,
    // Tunnel overrides
    val mtu: Int = 0, // 0 = auto (use core-provided MTU)
    val customDns: String = "" // "" = use core/fallback DNS
) {
    val isValid: Boolean
        get() = serverAddress.isNotBlank() && serverPort in 1..65535 && secret.isNotBlank()

    val serverEndpoint: String
        get() = "$serverAddress:$serverPort"

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("serverAddress", serverAddress)
            put("serverPort", serverPort)
            put("secret", secret)
            put("strategy", strategy)
            put("enableQuic", enableQuic)
            put("quicPort", quicPort)
            put("coverHost", coverHost)
            put("rttMasking", rttMasking)
            put("rttProfile", rttProfile)
            put("fallbackEnabled", fallbackEnabled)
            put("debugLogging", debugLogging)
            put("lastLatencyMs", lastLatencyMs)
            put("connectionMode", connectionMode)
            put("proxyPort", proxyPort)
            // Port hopping
            put("portHoppingEnabled", portHoppingEnabled)
            put("portHopRangeStart", portHopRangeStart)
            put("portHopRangeEnd", portHopRangeEnd)
            put("portHopIntervalMs", portHopIntervalMs)
            put("portHopStrategy", portHopStrategy)
            portHopSeed?.let { put("portHopSeed", it) }
            // Traffic shaper
            put("shaperPreset", shaperPreset)
            put("shaperSeed", shaperSeed)
            // ECH
            put("echEnabled", echEnabled)
            put("echConfig", echConfig)
            put("echPublicName", echPublicName)
            // IPv6 endpoint
            put("serverAddressV6", serverAddressV6)
            put("preferIpv6", preferIpv6)
            put("fallbackV4", fallbackV4)
            // IPv6 inside the tunnel
            put("tunnelIpv6", tunnelIpv6)
            // QUIC SNI fragmentation
            put("quicSniFrag", quicSniFrag)
            // Tunnel overrides
            put("mtu", mtu)
            put("customDns", customDns)
        }
    }

    /**
     * Serialize this config into a shareable tired:// URL.
     * Only non-default optional parameters are included to keep the link short.
     * Roundtrips with [fromUrl].
     */
    fun toUrl(): String {
        val params = mutableListOf<String>()
        params.add("secret=" + Uri.encode(secret))
        if (name.isNotBlank() && name != "Server") params.add("name=" + Uri.encode(name))
        if (strategy != "auto") params.add("strategy=" + Uri.encode(strategy))
        if (!enableQuic) params.add("quic=false")
        if (quicPort != 443) params.add("quicPort=$quicPort")
        if (coverHost != "api.googleapis.com") params.add("cover=" + Uri.encode(coverHost))
        if (rttMasking) {
            params.add("rtt=true")
            if (rttProfile != "moscow-yandex") params.add("rttProfile=" + Uri.encode(rttProfile))
        }
        if (!fallbackEnabled) params.add("fallback=false")
        if (debugLogging) params.add("debug=true")
        if (connectionMode != "tun") params.add("mode=" + Uri.encode(connectionMode))
        if (proxyPort != 8080) params.add("proxyPort=$proxyPort")
        if (portHoppingEnabled) {
            params.add("hop=true")
            if (portHopRangeStart != 47000) params.add("hopStart=$portHopRangeStart")
            if (portHopRangeEnd != 65535) params.add("hopEnd=$portHopRangeEnd")
            if (portHopIntervalMs != 60_000L) params.add("hopInterval=$portHopIntervalMs")
            if (portHopStrategy != "random") params.add("hopStrategy=" + Uri.encode(portHopStrategy))
            portHopSeed?.takeIf { it.isNotEmpty() }?.let { params.add("hopSeed=" + Uri.encode(it)) }
        }
        if (shaperPreset.isNotEmpty()) params.add("shaper=" + Uri.encode(shaperPreset))
        if (shaperSeed != 0L) params.add("shaperSeed=$shaperSeed")
        if (echEnabled) {
            params.add("ech=true")
            if (echConfig.isNotEmpty()) params.add("echConfig=" + Uri.encode(echConfig))
            if (echPublicName != "cloudflare-ech.com") params.add("echPublicName=" + Uri.encode(echPublicName))
        }
        if (serverAddressV6.isNotEmpty()) params.add("serverV6=" + Uri.encode(serverAddressV6))
        if (preferIpv6) params.add("preferIpv6=true")
        if (!fallbackV4) params.add("fallbackV4=false")
        if (tunnelIpv6 != "off") params.add("tunIpv6=" + Uri.encode(tunnelIpv6))
        if (quicSniFrag) params.add("quicSniFrag=true")
        if (mtu != 0) params.add("mtu=$mtu")
        if (customDns.isNotEmpty()) params.add("dns=" + Uri.encode(customDns))

        return "tired://$serverAddress:$serverPort?" + params.joinToString("&")
    }

    companion object {
        // Connection modes
        val CONNECTION_MODES = listOf(
            "tun" to "VPN (Full Tunnel)",
            "proxy" to "HTTP Proxy"
        )

        // Available strategies
        val STRATEGIES = listOf(
            "auto" to "Auto (Best Available)",
            "reality" to "REALITY Protocol",
            "seqovl" to "Seqovl (sequence overlap)",
            "quic" to "QUIC Tunnel",
            "websocket_padded" to "WebSocket Salamander",
            "http2_stego" to "HTTP/2 Steganography",
            "morph_Yandex Video" to "Traffic Morph (Yandex)",
            "morph_VK Video" to "Traffic Morph (VK)",
            "geneva_russia" to "Geneva (Russia TSPU)",
            "antiprobe" to "Anti-Probe Resistance",
            "confusion_0" to "Protocol Confusion (DNS/TLS)"
        )

        val RTT_PROFILES = listOf(
            "moscow-yandex" to "Moscow - Yandex",
            "moscow-vk" to "Moscow - VK",
            "regional-russia" to "Regional Russia",
            "siberia" to "Siberia",
            "cdn" to "CDN"
        )

        fun fromJson(json: JSONObject): VpnConfig {
            return VpnConfig(
                id = json.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name = json.optString("name", "Server"),
                serverAddress = json.optString("serverAddress", ""),
                serverPort = json.optInt("serverPort", 993),
                secret = json.optString("secret", ""),
                strategy = json.optString("strategy", "auto"),
                enableQuic = json.optBoolean("enableQuic", true),
                quicPort = json.optInt("quicPort", 443),
                coverHost = json.optString("coverHost", "api.googleapis.com"),
                rttMasking = json.optBoolean("rttMasking", false),
                rttProfile = json.optString("rttProfile", "moscow-yandex"),
                fallbackEnabled = json.optBoolean("fallbackEnabled", true),
                debugLogging = json.optBoolean("debugLogging", false),
                lastLatencyMs = json.optLong("lastLatencyMs", -1),
                connectionMode = json.optString("connectionMode", "tun"),
                proxyPort = json.optInt("proxyPort", 8080),
                // Port hopping
                portHoppingEnabled = json.optBoolean("portHoppingEnabled", false),
                portHopRangeStart = json.optInt("portHopRangeStart", 47000),
                portHopRangeEnd = json.optInt("portHopRangeEnd", 65535),
                portHopIntervalMs = json.optLong("portHopIntervalMs", 60_000L),
                portHopStrategy = json.optString("portHopStrategy", "random"),
                portHopSeed = json.optString("portHopSeed", null).takeIf { !it.isNullOrEmpty() },
                // Traffic shaper
                shaperPreset = json.optString("shaperPreset", ""),
                shaperSeed = json.optLong("shaperSeed", 0L),
                // ECH
                echEnabled = json.optBoolean("echEnabled", false),
                echConfig = json.optString("echConfig", ""),
                echPublicName = json.optString("echPublicName", "cloudflare-ech.com"),
                // IPv6 endpoint
                serverAddressV6 = json.optString("serverAddressV6", ""),
                preferIpv6 = json.optBoolean("preferIpv6", false),
                fallbackV4 = json.optBoolean("fallbackV4", true),
                // IPv6 inside the tunnel
                tunnelIpv6 = json.optString("tunnelIpv6", "off"),
                // QUIC SNI fragmentation
                quicSniFrag = json.optBoolean("quicSniFrag", false),
                // Tunnel overrides
                mtu = json.optInt("mtu", 0),
                customDns = json.optString("customDns", "")
            )
        }

        // Port hopping strategies
        val PORT_HOP_STRATEGIES = listOf(
            "random" to "Random",
            "sequential" to "Sequential",
            "fibonacci" to "Fibonacci"
        )

        // Matches a tired:// link embedded in surrounding text: a chat message, a
        // JSON string, a shell here-doc.
        //
        // A link ends at whitespace or at one of the characters that can only be
        // structure around it. Square brackets are NOT in that set - an IPv6
        // authority is written [2001:db8::1]:995 - and neither are a comma, an
        // apostrophe or a closing parenthesis, which Android's Uri.encode leaves
        // literal inside a name or a secret.
        private val TIRED_URL_REGEX =
            Regex("""tired://[^\s"<>\\`|^{}]+""", RegexOption.IGNORE_CASE)

        /**
         * Extract every tired:// link from arbitrary clipboard/shared text, in the
         * order they appear. A link ends at the first delimiter, so a
         * newline-separated list, a single link, a link inside a JSON string and a
         * link wrapped in a chat message all come out the same way.
         */
        fun extractTiredUrls(text: String?): List<String> {
            if (text.isNullOrBlank()) return emptyList()
            return TIRED_URL_REGEX.findAll(text).map { it.value }.toList()
        }

        /**
         * Extract a tired:// link from arbitrary clipboard/shared text.
         * Returns null if no tired:// link is present.
         */
        fun extractTiredUrl(text: String?): String? = extractTiredUrls(text).firstOrNull()

        /**
         * Parse a tired:// URL into a VpnConfig. Tolerant of surrounding text via [extractTiredUrl].
         * Returns null if the link is missing, malformed, or lacks server/secret.
         */
        fun fromUrl(rawUrl: String?): VpnConfig? {
            val url = extractTiredUrl(rawUrl) ?: return null
            return try {
                val uri = Uri.parse(url)
                if (!uri.scheme.equals("tired", ignoreCase = true)) return null

                // Resolve host:port. Fall back to manual authority parsing if Uri.host is null
                // (can happen for some hostnames Android's parser rejects).
                var host = uri.host
                var port = uri.port
                if (host.isNullOrBlank()) {
                    val authority = uri.authority
                        ?: url.removePrefix("tired://").substringBefore("?").substringBefore("/")
                    val hostPort = authority.substringAfterLast('@') // drop optional userinfo
                    if (hostPort.startsWith("[")) {
                        // IPv6 literal [::1]:port
                        host = hostPort.substringAfter('[').substringBefore(']')
                        port = hostPort.substringAfterLast("]:", "").toIntOrNull() ?: -1
                    } else {
                        host = hostPort.substringBeforeLast(':', hostPort)
                        port = hostPort.substringAfterLast(':', "").toIntOrNull() ?: -1
                    }
                }
                if (host.isNullOrBlank()) return null
                val resolvedPort = port.takeIf { it in 1..65535 } ?: 993

                val secret = uri.getQueryParameter("secret") ?: return null
                if (secret.isBlank()) return null

                VpnConfig(
                    name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: host,
                    serverAddress = host,
                    serverPort = resolvedPort,
                    secret = secret,
                    strategy = uri.getQueryParameter("strategy") ?: "auto",
                    enableQuic = uri.getQueryParameter("quic")?.toBooleanStrictOrNull() ?: true,
                    quicPort = uri.getQueryParameter("quicPort")?.toIntOrNull() ?: 443,
                    coverHost = uri.getQueryParameter("cover") ?: "api.googleapis.com",
                    rttMasking = uri.getQueryParameter("rtt")?.toBooleanStrictOrNull() ?: false,
                    rttProfile = uri.getQueryParameter("rttProfile") ?: "moscow-yandex",
                    fallbackEnabled = uri.getQueryParameter("fallback")?.toBooleanStrictOrNull() ?: true,
                    debugLogging = uri.getQueryParameter("debug")?.toBooleanStrictOrNull() ?: false,
                    connectionMode = uri.getQueryParameter("mode") ?: "tun",
                    proxyPort = uri.getQueryParameter("proxyPort")?.toIntOrNull() ?: 8080,
                    portHoppingEnabled = uri.getQueryParameter("hop")?.toBooleanStrictOrNull() ?: false,
                    portHopRangeStart = uri.getQueryParameter("hopStart")?.toIntOrNull() ?: 47000,
                    portHopRangeEnd = uri.getQueryParameter("hopEnd")?.toIntOrNull() ?: 65535,
                    portHopIntervalMs = uri.getQueryParameter("hopInterval")?.toLongOrNull() ?: 60_000L,
                    portHopStrategy = uri.getQueryParameter("hopStrategy") ?: "random",
                    portHopSeed = uri.getQueryParameter("hopSeed")?.takeIf { it.isNotEmpty() },
                    shaperPreset = uri.getQueryParameter("shaper") ?: "",
                    shaperSeed = uri.getQueryParameter("shaperSeed")?.toLongOrNull() ?: 0L,
                    echEnabled = uri.getQueryParameter("ech")?.toBooleanStrictOrNull() ?: false,
                    echConfig = uri.getQueryParameter("echConfig") ?: "",
                    echPublicName = uri.getQueryParameter("echPublicName") ?: "cloudflare-ech.com",
                    serverAddressV6 = uri.getQueryParameter("serverV6") ?: "",
                    preferIpv6 = uri.getQueryParameter("preferIpv6")?.toBooleanStrictOrNull() ?: false,
                    fallbackV4 = uri.getQueryParameter("fallbackV4")?.toBooleanStrictOrNull() ?: true,
                    tunnelIpv6 = uri.getQueryParameter("tunIpv6") ?: "off",
                    quicSniFrag = uri.getQueryParameter("quicSniFrag")?.toBooleanStrictOrNull() ?: false,
                    mtu = uri.getQueryParameter("mtu")?.toIntOrNull() ?: 0,
                    customDns = uri.getQueryParameter("dns") ?: ""
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
