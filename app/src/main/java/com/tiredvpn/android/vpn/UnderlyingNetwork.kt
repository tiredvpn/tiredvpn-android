package com.tiredvpn.android.vpn

/**
 * Which network the tunnel's traffic leaves on, as the core is told it.
 *
 * The core (tiredvpn 1.11.9, internal/tun/control.go) takes an optional
 * `network_id` on `connect`, `network_changed` and `reconnect`. It keeps what
 * one core learned about the servers - parks, route verdicts, swallow runs -
 * for the next core for two minutes, and only on the same network: what the
 * old network's path did to the servers says nothing about the new one. The
 * id is compared for equality and never parsed.
 *
 * The id has to name the UNDERLYING network, the one the core's protected
 * sockets go out on. With the tunnel up, `activeNetwork` can return the VPN's
 * own Network; naming that would make every network look like the same one,
 * and the core would carry a dead network's verdicts onto the next.
 *
 * Kept free of android.net so the choice can be tested on the JVM; the
 * service fills in the [Candidate]s from ConnectivityManager.
 */
internal object UnderlyingNetwork {

    /** One network as ConnectivityManager reports it. */
    data class Candidate<N>(
        val network: N,
        /** Network.getNetworkHandle(). */
        val handle: Long,
        val isVpn: Boolean,
        val hasInternet: Boolean,
        val validated: Boolean,
        val kind: NetworkInUse.Kind,
    )

    /**
     * The network the core's traffic leaves on, or null when there is none.
     *
     * In order:
     *  1. [tracked], the network the service's NetworkCallback has traffic on
     *     ([NetworkInUse]). It is the network the app reacts to, so it is the
     *     one a `network_changed` is about. Taken only while ConnectivityManager
     *     still lists it as a non-VPN network.
     *  2. [active], the system default, when it is not a VPN.
     *  3. The best non-VPN network with INTERNET in [all], ranked as
     *     NetworkInUse ranks them: validated first, then ethernet, Wi-Fi,
     *     mobile, other.
     */
    fun <N> choose(tracked: N?, active: N?, all: List<Candidate<N>>): Candidate<N>? {
        val usable = all.filter { !it.isVpn && it.hasInternet && it.handle != 0L }

        if (tracked != null) {
            usable.firstOrNull { it.network == tracked }?.let { return it }
        }
        if (active != null) {
            usable.firstOrNull { it.network == active }?.let { return it }
        }
        return usable.minWithOrNull(
            compareBy<Candidate<N>>({ if (it.validated) 0 else 1 }, { it.kind.rank })
        )
    }

    /**
     * The field's value for [handle]: decimal, as a string. Null - the field
     * is left out - when there is no network. The core reads "" or "0" as a
     * network like any other, so neither may stand in for "none".
     */
    fun wireId(handle: Long?): String? = handle?.takeIf { it != 0L }?.toString()
}
