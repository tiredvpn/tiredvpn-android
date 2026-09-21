package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three waits and the one ceiling that decide how long a phone keeps
 * trying, none of which had a test.
 *
 * The ceiling is the part with a user-visible consequence: crossing it does
 * not merely stop the loop, it tears the tunnel down with
 * [StopIntent.TECHNICAL] and puts the service into [VpnState.Error]. Off by
 * one in either direction is an extra ten seconds or a reconnect that never
 * happens.
 */
class ReconnectBackoffTest {

    private val cap = 10_000L

    // --- the ceiling ---------------------------------------------------------

    @Test
    fun `the ceiling is thirty attempts`() {
        assertEquals(30, ReconnectBackoff.MAX_ATTEMPTS)
    }

    /**
     * The boundary, both sides of it. The counter is incremented before the
     * check, so attempt 30 is the last one that runs and 31 is the one that
     * gives up — `>`, not `>=`.
     */
    @Test
    fun `attempt thirty still runs and thirty-one gives up`() {
        assertFalse(ReconnectBackoff.exhausted(29))
        assertFalse(ReconnectBackoff.exhausted(30))
        assertTrue(ReconnectBackoff.exhausted(31))
    }

    @Test
    fun `the first attempt is never exhausted`() {
        assertFalse(ReconnectBackoff.exhausted(1))
    }

    // --- scheduleAutoReconnect: 2s, 4s, 6s ... capped ------------------------

    @Test
    fun `the scheduled wait climbs two seconds per attempt`() {
        assertEquals(2_000L, ReconnectBackoff.scheduleDelayMs(1, cap))
        assertEquals(4_000L, ReconnectBackoff.scheduleDelayMs(2, cap))
        assertEquals(6_000L, ReconnectBackoff.scheduleDelayMs(3, cap))
        assertEquals(8_000L, ReconnectBackoff.scheduleDelayMs(4, cap))
    }

    @Test
    fun `the scheduled wait stops climbing at the cap`() {
        assertEquals(cap, ReconnectBackoff.scheduleDelayMs(5, cap))
        assertEquals(cap, ReconnectBackoff.scheduleDelayMs(6, cap))
        assertEquals(cap, ReconnectBackoff.scheduleDelayMs(ReconnectBackoff.MAX_ATTEMPTS, cap))
    }

    /**
     * Five is where the ramp meets the cap. Asserting the neighbours as well
     * keeps a change to the slope from hiding behind the cap.
     */
    @Test
    fun `the cap first bites on the fifth attempt`() {
        assertTrue(ReconnectBackoff.scheduleDelayMs(4, cap) < cap)
        assertEquals(cap, ReconnectBackoff.scheduleDelayMs(5, cap))
    }

    /**
     * The whole retry budget: thirty attempts at this ramp is 4m40s of
     * waiting, which is what makes 30 a defensible ceiling. If either the
     * slope or the ceiling moves, this number moves with it and the trade has
     * to be re-argued rather than re-typed.
     */
    @Test
    fun `thirty attempts add up to four minutes forty of waiting`() {
        val total = (1..ReconnectBackoff.MAX_ATTEMPTS).sumOf { ReconnectBackoff.scheduleDelayMs(it, cap) }

        assertEquals(280_000L, total)
    }

    // --- executeReconnectSequence: 500ms, then 2s, 3s ... capped -------------

    /**
     * The first attempt is deliberately short: by this point the sequence has
     * already spent its time on connectivity checks.
     */
    @Test
    fun `the sequence wait starts at half a second`() {
        assertEquals(500L, ReconnectBackoff.sequenceDelayMs(0, cap))
        assertEquals(500L, ReconnectBackoff.sequenceDelayMs(1, cap))
    }

    @Test
    fun `the sequence wait climbs one second per attempt after the first`() {
        assertEquals(2_000L, ReconnectBackoff.sequenceDelayMs(2, cap))
        assertEquals(3_000L, ReconnectBackoff.sequenceDelayMs(3, cap))
        assertEquals(9_000L, ReconnectBackoff.sequenceDelayMs(9, cap))
    }

    @Test
    fun `the sequence wait shares the same cap`() {
        assertEquals(cap, ReconnectBackoff.sequenceDelayMs(10, cap))
        assertEquals(cap, ReconnectBackoff.sequenceDelayMs(30, cap))
    }

    /**
     * The two ramps are not the same ramp, which is the reason there are two
     * functions. A refactor that collapses them would have to break this.
     */
    @Test
    fun `the sequence wait is never longer than the scheduled one`() {
        for (attempt in 1..ReconnectBackoff.MAX_ATTEMPTS) {
            assertTrue(
                "attempt $attempt: sequence wait overtook the scheduled wait",
                ReconnectBackoff.sequenceDelayMs(attempt, cap) <= ReconnectBackoff.scheduleDelayMs(attempt, cap),
            )
        }
        assertTrue(
            "and they must not be the same function",
            (1..5).any { ReconnectBackoff.sequenceDelayMs(it, cap) != ReconnectBackoff.scheduleDelayMs(it, cap) },
        )
    }

    // --- the TCP connectivity probe: 1s, 2s, 3s, and no further --------------

    @Test
    fun `the connectivity wait climbs to three seconds and stops`() {
        assertEquals(1_000L, ReconnectBackoff.connectivityWaitMs(1))
        assertEquals(2_000L, ReconnectBackoff.connectivityWaitMs(2))
        assertEquals(3_000L, ReconnectBackoff.connectivityWaitMs(3))
        assertEquals(3_000L, ReconnectBackoff.connectivityWaitMs(4))
    }

    /**
     * Its cap is its own, not [maxBackoffMs]. The call site only ever runs it
     * three times, so the whole probe loop costs at most six seconds — a fact
     * the connect budget depends on and nothing else states.
     */
    @Test
    fun `three connectivity probes cost six seconds at most`() {
        assertEquals(6_000L, (1..3).sumOf { ReconnectBackoff.connectivityWaitMs(it) })
    }
}
