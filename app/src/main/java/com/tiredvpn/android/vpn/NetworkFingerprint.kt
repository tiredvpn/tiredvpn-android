package com.tiredvpn.android.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Fingerprint of a network's addressing, used to decide whether an
 * onLinkPropertiesChanged callback describes a move worth reconnecting for.
 *
 * Kept free of framework types so it can be unit-tested: the caller maps
 * LinkAddress to (address, prefixLength) pairs.
 *
 * IPv4 is compared by full address. A v4 address change is always a real
 * change of where we sit on the network.
 *
 * IPv6 is compared by PREFIX only, never by full address. Android rotates
 * RFC 4941 temporary addresses inside one prefix several times an hour, and
 * comparing full v6 addresses would turn every rotation into a reconnect
 * storm. Moving to a different router or ISP changes the prefix, and that is
 * the event we want. This is also why the previous v4-only filter could not
 * simply be widened: on a home router handing out a short IPv6 lifetime, the
 * v6 address changes far more often than the v4 one.
 *
 * Link-local addresses (169.254/16, fe80::/10) are ignored for both families.
 * They exist precisely while an interface has no usable global address, so
 * reacting to them schedules a reconnect towards a network that cannot carry
 * traffic yet.
 */
internal object NetworkFingerprint {

    /**
     * Build a stable, order-independent fingerprint. Two calls produce equal
     * strings if and only if the significant addressing is the same.
     */
    fun of(addresses: List<Pair<InetAddress, Int>>): String =
        addresses
            .mapNotNull { (address, prefixLength) -> significantPart(address, prefixLength) }
            .distinct()
            .sorted()
            .joinToString(",")

    private fun significantPart(address: InetAddress, prefixLength: Int): String? {
        if (address.isLinkLocalAddress || address.isLoopbackAddress || address.isAnyLocalAddress) {
            return null
        }
        return when (address) {
            is Inet4Address -> "4:${address.hostAddress}"
            is Inet6Address -> "6:${prefixOf(address, prefixLength)}/${clampPrefix(prefixLength)}"
            else -> null
        }
    }

    /** Zero every bit past [prefixLength] so temporary-address rotation cancels out. */
    private fun prefixOf(address: Inet6Address, prefixLength: Int): String {
        val bits = clampPrefix(prefixLength)
        val bytes = address.address.copyOf()
        for (i in bytes.indices) {
            val keptBits = (bits - i * 8).coerceIn(0, 8)
            val mask = if (keptBits == 0) 0 else (0xFF shl (8 - keptBits)) and 0xFF
            bytes[i] = (bytes[i].toInt() and mask).toByte()
        }
        return InetAddress.getByAddress(bytes).hostAddress ?: bytes.joinToString("") { "%02x".format(it) }
    }

    private fun clampPrefix(prefixLength: Int): Int = prefixLength.coerceIn(0, 128)
}
