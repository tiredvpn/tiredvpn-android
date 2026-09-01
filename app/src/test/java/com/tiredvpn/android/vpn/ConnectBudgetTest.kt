package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relationships between these timeouts used to live in a hand-maintained
 * comment ("25 second timeout (must be < CONNECTION_TIMEOUT=30s)"). Nothing
 * enforced it, and the budget as a whole was never checked against what the
 * core actually spends.
 */
class ConnectBudgetTest {

    /**
     * The bug. A silent address costs the core one pre-flight probe plus two
     * dead transports before it reaches a verdict, and one Connect may pay
     * that for two candidates. The old 30s budget could not cover even one.
     */
    @Test
    fun `budget covers the core's worst case connect`() {
        assertEquals(23_000L, ConnectBudget.PER_CANDIDATE_MS)
        assertEquals(46_000L, ConnectBudget.CORE_WORST_CASE_MS)

        assertTrue(
            "connect budget ${ConnectBudget.CONNECT_TIMEOUT_MS}ms does not cover " +
                "the core's ${ConnectBudget.CORE_WORST_CASE_MS}ms worst case",
            ConnectBudget.CONNECT_TIMEOUT_MS > ConnectBudget.CORE_WORST_CASE_MS
        )
        assertTrue(
            "socket read timeout ${ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS}ms cuts " +
                "the core off before its ${ConnectBudget.CORE_WORST_CASE_MS}ms worst case",
            ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS > ConnectBudget.CORE_WORST_CASE_MS
        )
    }

    /**
     * The socket read must give up first, so the failure surfaces as a real
     * error from connectToControlSocket rather than as the outer withTimeout
     * cancelling the coroutine mid-step.
     */
    @Test
    fun `socket read gives up before the overall connect does`() {
        assertTrue(
            ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS < ConnectBudget.CONNECT_TIMEOUT_MS
        )
    }

    /**
     * The old budget, kept as the explicit thing this change moves away from.
     *
     * Note what it does NOT say: one candidate at 23s did fit inside the old
     * 25s socket read, with 2s to spare. What never fit is the core's actual
     * worst case of two candidates, and in the 2026-09-01 capture even a single
     * successful attempt overran, because the pre-flight probed the v6 family
     * for 6.2s before the scan of the v4 address started. Two seconds of margin
     * is not a budget.
     */
    @Test
    fun `the previous budget could not fit the core's worst case`() {
        val oldConnectTimeout = 30_000L
        val oldSocketRead = 25_000L

        assertTrue(oldConnectTimeout < ConnectBudget.CORE_WORST_CASE_MS)
        assertTrue(oldSocketRead < ConnectBudget.CORE_WORST_CASE_MS)

        // One candidate fit, but only just — 2s of headroom over 23s.
        assertTrue(oldSocketRead > ConnectBudget.PER_CANDIDATE_MS)
        assertTrue(oldSocketRead - ConnectBudget.PER_CANDIDATE_MS < 3_000L)

        // The new budget clears a whole extra candidate.
        assertTrue(
            ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS - ConnectBudget.CORE_WORST_CASE_MS > 0
        )
    }

    /**
     * Guards against silently drifting away from the core. If any of these
     * change in internal/strategy/strategy.go, this test should fail and force
     * the pair to be re-tuned together rather than one side at a time.
     */
    @Test
    fun `core constants are mirrored exactly`() {
        assertEquals(3_000L, ConnectBudget.CORE_PROBE_TIMEOUT_MS)
        assertEquals(10_000L, ConnectBudget.CORE_CONNECT_TIMEOUT_MS)
        assertEquals(2, ConnectBudget.CORE_SILENT_SCAN_ABORT)
        assertEquals(2, ConnectBudget.CORE_MAX_ENDPOINT_ATTEMPTS)
    }
}
