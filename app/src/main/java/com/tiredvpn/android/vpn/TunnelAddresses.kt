package com.tiredvpn.android.vpn

/**
 * Whether a reconnect's answer moved the tunnel's IPv4 address.
 *
 * Kept apart from the service for the same reason as [Ipv6Renegotiation]:
 * the decision is what a test can check, the interface rebuild is not.
 */
internal object TunnelAddresses {

    /**
     * True when [reported] names an address other than [current].
     *
     * An absent or empty answer says nothing, and an empty [current] means
     * no interface has been built for an address yet, so there is nothing
     * stale to replace.
     */
    fun ipv4Changed(current: String, reported: String?): Boolean =
        current.isNotEmpty() && !reported.isNullOrEmpty() && reported != current
}
