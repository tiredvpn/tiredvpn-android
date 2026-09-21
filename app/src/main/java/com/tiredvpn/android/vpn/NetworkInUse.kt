package com.tiredvpn.android.vpn

/**
 * Which of the networks that are up is the one traffic actually uses.
 *
 * The callback is registered on a request that matches every validated non-VPN
 * network, so it reports the background LTE link alongside the Wi-Fi the phone
 * is really on. The old bookkeeping kept one field, written by whichever
 * `onAvailable` fired last, and two decisions leaned on it:
 *
 *  - `onLost` asked whether the network that vanished was "the" one. With the
 *    last callback winning, LTE coming up behind live Wi-Fi made Wi-Fi's own
 *    loss look irrelevant, and losing the idle LTE link looked like the outage.
 *  - the fast path for a network switch compared link addresses against
 *    whatever that same field had last recorded, so a switch was often noticed
 *    only by the two-second poll.
 *
 * `NetworkCapabilities.getUnderlyingNetworks` would answer this directly and
 * arrived in API 34; this module ships to 24. So the ranking is reproduced from
 * what the callbacks already hand us, in the system's own order: a validated
 * network beats an unvalidated one, Ethernet beats Wi-Fi beats cellular, and a
 * tie is kept by the incumbent rather than by arrival order — a rule that
 * changes its mind on a tie is a reconnect generator.
 *
 * Generic over the network handle so it can be exercised without an
 * android.net.Network, which cannot be constructed in a unit test.
 */
internal class NetworkInUse<N : Any> {

    /**
     * Transport, spelled as the active-network monitor spells it so both
     * producers of a `network_changed` hand [NetworkTransition] the same words.
     */
    enum class Kind(val wireName: String, val rank: Int) {
        ETHERNET("ethernet", 0),
        WIFI("wifi", 1),
        CELLULAR("mobile", 2),
        OTHER("other", 3),
    }

    /** What an event did to the network in use. */
    enum class Change {
        /** Nothing traffic cares about: some other link came or went. */
        NONE,

        /** The first network came up, or traffic moved to a different one. */
        SWITCHED,

        /** The last one went away. */
        LOST,
    }

    private data class Entry<N>(val network: N, val kind: Kind, val validated: Boolean)

    private val up = LinkedHashMap<N, Entry<N>>()

    private var current: N? = null

    /** The network traffic uses, or null when nothing is up. */
    val inUse: N? get() = current

    /** Its transport, or null. */
    val inUseKind: Kind? get() = current?.let { up[it]?.kind }

    /** How many networks are up. For logging. */
    val size: Int get() = up.size

    /**
     * A network came up, or its capabilities changed. Both are the same event
     * here: they say what this network is and whether it is usable now.
     */
    fun available(network: N, kind: Kind, validated: Boolean): Change {
        up[network] = Entry(network, kind, validated)
        return recompute()
    }

    /** A network went away. */
    fun lost(network: N): Change {
        up.remove(network)
        return recompute()
    }

    /** Forget everything; the callback is being unregistered. */
    fun clear() {
        up.clear()
        current = null
    }

    private fun recompute(): Change {
        val previous = current
        val best = up.values.minWithOrNull(::compare)?.network
        current = best

        return when {
            best == previous -> Change.NONE
            best == null -> Change.LOST
            else -> Change.SWITCHED
        }
    }

    /**
     * Negative when [a] is the better candidate. The incumbent wins ties, so a
     * second equally good network does not move traffic on paper.
     */
    private fun compare(a: Entry<N>, b: Entry<N>): Int {
        if (a.validated != b.validated) return if (a.validated) -1 else 1
        if (a.kind.rank != b.kind.rank) return a.kind.rank - b.kind.rank
        if (a.network == current) return -1
        if (b.network == current) return 1
        return 0
    }

    companion object {
        /**
         * @param ethernet/wifi/cellular what the capabilities report. Taken as
         *   booleans rather than a NetworkCapabilities so the ranking can be
         *   tested; the class is final and cannot be built in a unit test.
         */
        fun kindOf(ethernet: Boolean, wifi: Boolean, cellular: Boolean): Kind = when {
            ethernet -> Kind.ETHERNET
            wifi -> Kind.WIFI
            cellular -> Kind.CELLULAR
            else -> Kind.OTHER
        }
    }
}
