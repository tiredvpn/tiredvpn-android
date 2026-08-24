package com.tiredvpn.android.vpn

import android.net.VpnService

/**
 * IPv6 leak protection for the VPN interface (issue #55).
 *
 * The tunnel carries IPv4 only. A Builder that declares no IPv6 address and no
 * IPv6 route leaves the device's native IPv6 default route untouched, so on a
 * dual-stack network every AAAA-capable destination is reached outside the
 * tunnel, with the real address.
 *
 * VpnService has no "blackhole route" API, so the usual workaround is to claim
 * ::/0 with a ULA address that nothing ever answers on: IPv6 packets are pulled
 * into the TUN and dropped by the core instead of escaping to the carrier.
 *
 * Dual-stack *inside* the tunnel (`-tun-ipv6 dual`, handshake v0x04) replaces
 * this guard: when the core negotiates real v6 addresses, establishVpn()
 * installs them and does NOT call [blackholeIpv6]. This guard stays the
 * default for v4-only sessions (policy off, old cores, v4-only exits).
 */
object Ipv6Guard {

    /**
     * Tunnel-local ULA (fc00::/7, so it is never globally routable). Traffic sent
     * to it goes nowhere by design.
     */
    const val BLACKHOLE_ADDRESS = "fd00:71ed:71ed::1"

    /** /128: a single address, no on-link prefix that could attract neighbours. */
    const val BLACKHOLE_PREFIX_LENGTH = 128

    /**
     * RFC 8200 minimum link MTU. The kernel disables IPv6 on an interface below
     * this, which makes both the address and the route un-addable.
     */
    const val MIN_IPV6_MTU = 1280

    /**
     * Whether the interface can hold the blackhole configuration at this MTU.
     * `mtu <= 0` means "MTU not set explicitly", where the platform default
     * already clears the minimum.
     */
    fun canBlackhole(mtu: Int): Boolean = mtu <= 0 || mtu >= MIN_IPV6_MTU

    /**
     * Claim all IPv6 traffic so it dies inside the tunnel instead of leaking.
     * Call on every Builder before establish().
     *
     * @return true if the blackhole was installed, false if the MTU is too low
     *         for IPv6 and the caller should warn about the remaining leak.
     */
    fun blackholeIpv6(builder: VpnService.Builder, mtu: Int): Boolean {
        if (!canBlackhole(mtu)) return false
        builder.addAddress(BLACKHOLE_ADDRESS, BLACKHOLE_PREFIX_LENGTH)
        builder.addRoute("::", 0)
        return true
    }

    /**
     * True for an IPv6 literal - "2001:db8::1", "[::1]", "fe80::1%wlan0".
     *
     * A resolver behind the blackhole is unreachable, so such an entry must never
     * reach addDnsServer(): the system would send queries into the tunnel where
     * they are dropped, and resolution would stall instead of falling back.
     */
    fun isIpv6Literal(address: String): Boolean =
        address.trim()
            .removeSurrounding("[", "]")
            .substringBefore('%')
            .contains(':')
}
