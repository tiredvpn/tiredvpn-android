package com.tiredvpn.android.vpn

/**
 * How long the service waits for the core to finish one connect.
 *
 * Derived from the core's own Android profile rather than picked. Every factor
 * has a named counterpart in internal/strategy/strategy.go:
 *
 *   probeTimeout           = 3s  — pre-flight probe of one address
 *   connectTimeout         = 10s — one strategy attempt
 *   androidSilentScanAbort = 2   — a silent address is abandoned once two
 *                                  transports have died on connectTimeout
 *   maxEndpointAttempts    = 2   — one Connect walks at most two candidates
 *
 * The previous budget was 30s overall with a 25s socket read, and it was short
 * by exactly the pre-flight probe: the core's comment on androidSilentScanAbort
 * budgets ~26s for the scan and does not count the probing that precedes it.
 * The 2026-09-01 capture shows the arithmetic — 6.2s spent probing a silent
 * IPv6 address, then a scan cut off at 19.9s, so the service gave up within a
 * second of the core reaching its own verdict and discarded the answer.
 *
 * These numbers and the core's androidSilentScanAbort are a tuned pair: with a
 * longer budget the core could now afford a third dead transport before writing
 * an address off. That is a separate decision and is deliberately not taken
 * here — see the report accompanying this change.
 */
internal object ConnectBudget {

    const val CORE_PROBE_TIMEOUT_MS = 3_000L
    const val CORE_CONNECT_TIMEOUT_MS = 10_000L
    const val CORE_SILENT_SCAN_ABORT = 2
    const val CORE_MAX_ENDPOINT_ATTEMPTS = 2

    /** Worst case one candidate may cost: one probe plus a full silent scan. */
    const val PER_CANDIDATE_MS =
        CORE_PROBE_TIMEOUT_MS + CORE_SILENT_SCAN_ABORT * CORE_CONNECT_TIMEOUT_MS

    /** Worst case one Connect may cost inside the core: 2 * 23s = 46s. */
    const val CORE_WORST_CASE_MS = CORE_MAX_ENDPOINT_ATTEMPTS * PER_CANDIDATE_MS

    /**
     * Read timeout on the control socket. Must outlast one full Connect, since
     * the core answers only once the scan is done, and must stay below
     * [CONNECT_TIMEOUT_MS] so the outer fence is the one that reports failure.
     */
    const val CONTROL_SOCKET_READ_TIMEOUT_MS = CORE_WORST_CASE_MS + 4_000L

    /** The whole connect: the core's worst case plus TUN setup and handshake. */
    const val CONNECT_TIMEOUT_MS = CORE_WORST_CASE_MS + 14_000L
}
