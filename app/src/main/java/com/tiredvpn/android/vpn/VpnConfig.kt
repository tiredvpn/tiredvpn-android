package com.tiredvpn.android.vpn

import android.content.Context
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
            // QUIC SNI fragmentation
            put("quicSniFrag", quicSniFrag)
            // Tunnel overrides
            put("mtu", mtu)
            put("customDns", customDns)
        }
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
    }
}
