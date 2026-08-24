package com.tiredvpn.android.vpn

/**
 * Addressing and DNS plan for the VPN interface, derived from what the core
 * negotiated over the control socket.
 *
 * When the core answers the v0x04 handshake with dual-stack addresses
 * ([TiredVpnService.TunnelConfig.ip6] non-null), the interface gets the real
 * v6 address and a ::/0 route into the tunnel, and the [Ipv6Guard] leak
 * blackhole is NOT installed - real v6 replaces it. Without a negotiated v6
 * (old core, policy off, or an exit that declined), the plan is byte-for-byte
 * today's v4-only behaviour, blackhole included.
 *
 * Pure logic, no Android types - unit-testable on the JVM like [Ipv6Guard].
 */
object DualStackPlan {

    /**
     * Prefix length for the negotiated v6 tunnel address. VpnService has no
     * point-to-point mode, so /64 (the Go client's fallback shape) is the
     * pragmatic choice; the blackhole's /128 is only for a dead address.
     */
    const val IPV6_PREFIX_LENGTH = 64

    data class Plan(
        /** True when real v6 is installed on the interface (negotiated AND MTU allows). */
        val dualStack: Boolean,
        /** Negotiated client v6 address; non-null only when [dualStack]. */
        val ip6: String?,
        /** Negotiated server v6 address (informational, e.g. for logs). */
        val serverIp6: String?,
        /** True when the [Ipv6Guard] blackhole must be installed instead of real v6. */
        val blackhole: Boolean,
        /** DNS the caller asked for (custom override or core-provided). */
        val requestedDns: String,
        /** Effective primary DNS for addDnsServer(). */
        val dns: String,
        /** True when a v6 DNS literal was swapped for the v4 fallback. */
        val dnsSwappedToFallback: Boolean
    )

    /**
     * Compute the plan for one establish() call.
     *
     * @param tunConfig    what the core sent over the control socket
     * @param customDns    user's custom DNS override ("" = use core DNS)
     * @param fallbackDns  v4 fallback resolver
     * @param mtu          effective interface MTU (below 1280 the kernel
     *                     disables IPv6 on the interface entirely)
     */
    fun create(
        tunConfig: TiredVpnService.TunnelConfig,
        customDns: String,
        fallbackDns: String,
        mtu: Int
    ): Plan {
        val negotiated = !tunConfig.ip6.isNullOrBlank()
        // Same gate as the guard: the kernel rejects v6 config below the
        // RFC 8200 minimum MTU, so a negotiated dual-stack degrades to the
        // blackhole there instead of breaking establish().
        val dualStack = negotiated && Ipv6Guard.canBlackhole(mtu)

        val requestedDns = customDns.takeIf { it.isNotBlank() } ?: tunConfig.dns
        // A v6 resolver behind the blackhole never answers; only swap it out
        // when real v6 is not being installed.
        val swap = !dualStack && Ipv6Guard.isIpv6Literal(requestedDns)

        return Plan(
            dualStack = dualStack,
            ip6 = tunConfig.ip6?.takeIf { dualStack },
            serverIp6 = tunConfig.serverIp6?.takeIf { dualStack },
            blackhole = !dualStack,
            requestedDns = requestedDns,
            dns = if (swap) fallbackDns else requestedDns,
            dnsSwappedToFallback = swap
        )
    }
}
