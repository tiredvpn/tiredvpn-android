package com.tiredvpn.android.vpn

/**
 * What `TiredVpnService.resolveServerEndpoint` decides before it touches DNS.
 *
 * Split out from the service because the decision is pure text and the rest of
 * that function is `InetAddress.getAllByName` — untestable here, and not the
 * part that has ever been wrong.
 *
 * This object describes the parser as it is, not as it should be. It is worth
 * being explicit about that, because [plan] rejects every IPv6 address this
 * app can store: the split is on EVERY colon and a result of other than two
 * parts is malformed, so `[2001:db8::1]:995` is malformed, `2001:db8::1` is
 * malformed, and only `host:port` and `1.2.3.4:port` survive. Changing that is
 * a behaviour change and belongs in its own review; EndpointResolutionTest
 * pins the current answers so that change cannot happen by accident.
 */
internal object EndpointResolution {

    /** What the caller should do with an endpoint. */
    sealed interface Plan {

        /** Not `host:port` as this parser understands it. The connect fails. */
        data object Malformed : Plan

        /**
         * An IPv4 dotted quad: hand it back untouched, no lookup.
         *
         * "Dotted quad" is 1-3 digits four times over and nothing more, so
         * `999.1.2.3` takes this branch too. Harmless — it is passed straight
         * to the core, which fails on it the same way DNS would.
         */
        data class AlreadyLiteral(val endpoint: String) : Plan

        /** A name to look up, with the port to put back afterwards. */
        data class Resolve(val host: String, val port: String) : Plan
    }

    private val IPV4 = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    fun plan(endpoint: String): Plan {
        val parts = endpoint.split(":")
        if (parts.size != 2) return Plan.Malformed

        val host = parts[0]
        val port = parts[1]

        if (host.matches(IPV4)) return Plan.AlreadyLiteral(endpoint)

        return Plan.Resolve(host, port)
    }
}

/**
 * How long a reconnect waits, and when it stops trying.
 *
 * Three formulas lived inline in TiredVpnService, each written as a `minOf`
 * next to a comment restating it ("Exponential backoff: 2s, 4s, 6s, 8s, 10s
 * (capped)"). None of them is exponential, and nothing checked the comments
 * against the arithmetic. They are here so a test can, and so the ceiling that
 * ends the reconnect loop for good is a named constant rather than a local
 * `val` halfway down a 90-line function.
 */
internal object ReconnectBackoff {

    /**
     * Attempts allowed before the service gives up and tears down with
     * [StopIntent.TECHNICAL].
     *
     * Thirty is not arbitrary: with [scheduleDelayMs] capped at 10s it buys
     * roughly five minutes of retrying, which is the length of a tunnel or a
     * lift. Raising it past that keeps a dead server warm; lowering it drops
     * the user in the one situation the retry loop exists for.
     */
    const val MAX_ATTEMPTS = 30

    /** True once [attempt] has gone past the ceiling. The attempt is 1-based. */
    fun exhausted(attempt: Int): Boolean = attempt > MAX_ATTEMPTS

    /**
     * The wait before a scheduled auto-reconnect: 2s, 4s, 6s ... capped.
     *
     * Linear, despite the comment at the call site calling it exponential.
     */
    fun scheduleDelayMs(attempt: Int, capMs: Long): Long = minOf(2_000L * attempt, capMs)

    /**
     * The wait inside a reconnect sequence that has already run its
     * connectivity checks: 500ms for the first attempt, then 1s, 2s, 3s ...
     * capped.
     *
     * Shorter than [scheduleDelayMs] on purpose — by this point the sequence
     * has spent seconds proving the network is back, and the user is watching.
     */
    fun sequenceDelayMs(attempt: Int, capMs: Long): Long =
        if (attempt <= 1) 500L else minOf(1_000L * attempt, capMs)

    /** The wait between TCP connectivity probes: 1s, 2s, 3s, and no further. */
    fun connectivityWaitMs(attempt: Int): Long = minOf(1_000L * attempt, 3_000L)
}
