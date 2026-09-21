package com.tiredvpn.android.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * The single gate every "the network changed" signal passes through.
 *
 * Two independent producers report the same event: the ConnectivityManager
 * callback (a system thread) and the 2-second active-network poll (a
 * coroutine). The old gate read `lastNetworkChangeTime`, compared it, and
 * assigned it in three separate steps, so both could pass, both could build a
 * ParcelFileDescriptor and both could hand the core a competing TUN
 * descriptor. Claiming the slot with a compare-and-set makes exactly one of
 * them win.
 */
internal class NetworkChangeGate(
    private val debounceMs: Long = 3_000L,
    private val postConnectQuietMs: Long = 3_000L,
) {

    private val lastAccepted = AtomicLong(0)

    /**
     * @param now              current time
     * @param connectedAt      when the tunnel came up (0 = never)
     * @param bypassDebounce   forced or critical events skip the quiet periods
     *                         but still have to win the claim
     * @return true for the one caller allowed to act on this event
     */
    fun tryEnter(now: Long, connectedAt: Long, bypassDebounce: Boolean): Boolean {
        while (true) {
            val previous = lastAccepted.get()
            // A plain compare-and-set is not enough on its own: two threads
            // reporting the same event carry the same timestamp, and the
            // second one's CAS from 500_000 to 500_000 would succeed. The
            // marker has to move strictly forward for a claim to count.
            if (now <= previous) return false
            if (!bypassDebounce) {
                if (connectedAt > 0 && now - connectedAt < postConnectQuietMs) return false
                if (previous > 0 && now - previous < debounceMs) return false
            }
            // Whoever moves the marker owns the event; the loser re-reads and
            // finds the marker at or past its own timestamp.
            if (lastAccepted.compareAndSet(previous, now)) return true
        }
    }

    /** Last accepted event time, for logging. */
    val lastAcceptedAt: Long get() = lastAccepted.get()
}

/**
 * Whether a lost network is one we have to react to.
 *
 * `onLost` is registered on a NetworkRequest that matches every validated
 * non-VPN network, so it fires for the LTE link disappearing while Wi-Fi is
 * perfectly alive. The old handler cleared the current network and started the
 * recovery machinery for any of them.
 */
internal object NetworkLossPolicy {

    /**
     * @param lost            the network the callback reported
     * @param inUse           the network we last saw come up, or null
     * @param stillConnected  is there any usable network left
     *
     * Reacting when [inUse] is unknown and connectivity is gone keeps the old
     * conservative behaviour for the case we genuinely cannot tell apart.
     */
    fun isRelevant(lost: Any?, inUse: Any?, stillConnected: Boolean): Boolean =
        (inUse != null && lost == inUse) || !stillConnected
}
