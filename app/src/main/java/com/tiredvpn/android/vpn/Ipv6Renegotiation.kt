package com.tiredvpn.android.vpn

/**
 * What to do when the core reports a different dual-stack pair than the one
 * the TUN interface was built with.
 *
 * A VpnService interface cannot have its addresses changed in place — the only
 * way to move to a new v6 address is `Builder.establish()` again and hand the
 * new descriptor over. So the question is narrow and worth getting exactly
 * right: has the pair actually changed, or are we about to rebuild the tunnel
 * for nothing (and, since the rebuild itself makes the core reconnect and
 * answer with a pair, possibly forever)?
 *
 * The core reports the pair on two channels, and they are not interchangeable:
 *
 *  - The `ipv6_changed` event, emitted from `doAutoReconnect` with
 *    `{"ip6":"…","server_ip6":"…"}` where an empty string means "no v6". It
 *    fires only when the pair changed, so an empty value there is removal.
 *  - The response to `network_changed` / `reconnect`, where `ip6`,
 *    `server_ip6` and `ipv6_removed` are all `omitempty`. An absent `ip6` says
 *    nothing at all — every v4-only session sends one — and removal is stated
 *    by `ipv6_removed: true`.
 *
 * Hence the distinction between a null field (not reported) and an empty one
 * (reported as none). Collapsing them would tear the v6 configuration down on
 * every reconnect of a v4-only tunnel.
 */
internal object Ipv6Renegotiation {

    /**
     * @param ip6                 address to configure, "" for none
     * @param serverIp6           peer address, "" for none
     * @param interfaceMustBeRebuilt true when the pair differs from what the
     *        interface currently has, i.e. only then is a rebuild warranted
     */
    data class Outcome(
        val ip6: String,
        val serverIp6: String,
        val interfaceMustBeRebuilt: Boolean,
    )

    /**
     * @param currentIp6        what the interface is configured with, "" if none
     * @param currentServerIp6  ditto for the peer
     * @param reportedIp6       null when the core said nothing about it
     * @param reportedServerIp6 null when the core said nothing about it
     * @param removed           the response's explicit `ipv6_removed` flag
     */
    fun apply(
        currentIp6: String,
        currentServerIp6: String,
        reportedIp6: String?,
        reportedServerIp6: String?,
        removed: Boolean,
    ): Outcome {
        if (removed) {
            // Explicit: the reconnect renegotiated the session without
            // dual-stack. Holding the old address points the tunnel's v6
            // default route at an exit that no longer carries it.
            return Outcome("", "", interfaceMustBeRebuilt = currentIp6.isNotEmpty() || currentServerIp6.isNotEmpty())
        }

        // A field the core did not mention keeps the value in force. This is
        // the whole "absent is not none" rule, and it lives here rather than in
        // an early return for "both absent": that return was unreachable as a
        // behaviour - it produced exactly what these two lines produce - and an
        // unreachable guard reads like protection while protecting nothing.
        val ip6 = reportedIp6 ?: currentIp6
        val serverIp6 = reportedServerIp6 ?: currentServerIp6
        // Equality is the loop breaker: the rebuild sends `network_changed`,
        // which makes the core reconnect and answer with a pair again. If that
        // pair matches what we just applied, this returns false and the
        // exchange stops.
        val changed = ip6 != currentIp6 || serverIp6 != currentServerIp6
        return Outcome(ip6, serverIp6, interfaceMustBeRebuilt = changed)
    }
}
