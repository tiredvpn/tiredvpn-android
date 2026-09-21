package com.tiredvpn.android.porthopping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [PortHopper] actually computes, as against what its header claims.
 *
 * The header says the class "MUST generate the same port sequence as the Go
 * version when using the same seed", and the file this one replaces
 * (PortHopperSyncTest) called itself "the golden test for cross-platform
 * compatibility". It asserted `port in range` and nothing else, so it agreed
 * with the claim without ever testing it. The claim is false twice over:
 *
 *  1. Different generators. Kotlin seeds `kotlin.random.Random` (XorWow); Go
 *     seeds `math/rand` (an additive lagged Fibonacci generator). Identical
 *     seed bytes, identical SHA-256, identical BigEndian int64 — and then two
 *     unrelated streams.
 *  2. Different filters. Go's `calculateNextPort` re-draws while the result is
 *     `ReservedPort443`, because 443 collides with the fixed default listener.
 *     Kotlin has no such loop and will hand out 443.
 *
 * So this file pins the divergence rather than the sync. If someone makes the
 * two sides agree, every test here goes red and has to be rewritten by hand —
 * which is the point: agreement is a change worth noticing, not a silent one.
 *
 * Neither generator is reachable from the app today; see the guards in
 * VpnCoreCallSiteTest. That is why the divergence is recorded instead of
 * fixed.
 */
class PortHopperDivergenceTest {

    /**
     * Measured, not quoted: built from `internal/porthopping/{hopper,config,
     * errors}.go` as they stand at origin/main of the core repo and run under
     * go1.26.7 on 2026-09-21. Only the `internal/log` import was stubbed.
     *
     * Regenerating these means running the real Go package again; do not
     * "correct" them from the Kotlin side.
     */
    private object GoReference {
        /** seed "test-seed-for-sync-12345", range 47000..47100, RANDOM. */
        val randomSequence = listOf(47021, 47026, 47018, 47043, 47008, 47036, 47016, 47027, 47099, 47074)

        /** range 440..446, SEQUENTIAL. Note the hole where 443 would be. */
        val sequentialAcross443 = listOf(441, 442, 444, 445, 446, 440, 441, 442, 444, 445)
    }

    private fun config(
        start: Int,
        end: Int,
        strategy: HopStrategy,
        seed: String?,
    ) = PortHopperConfig(
        enabled = true,
        portRangeStart = start,
        portRangeEnd = end,
        hopIntervalMs = 60_000L,
        strategy = strategy,
        seed = seed?.toByteArray(Charsets.US_ASCII),
    )

    private fun sequence(hopper: PortHopper, n: Int): List<Int> =
        buildList {
            add(hopper.currentPort())
            repeat(n - 1) { add(hopper.nextPort()) }
        }

    // --- 1. the seeded stream, pinned -----------------------------------------

    /**
     * The golden the old file should have had. It is the Kotlin stream and
     * nothing else: the draw order is `nextInt` for the port, then `nextDouble`
     * inside `randomizeInterval`, and both come off the same generator, so a
     * change to the jitter arithmetic moves the ports too.
     */
    @Test
    fun `the seeded Kotlin stream is exactly this`() {
        val hopper = PortHopper(config(47000, 47100, HopStrategy.RANDOM, "test-seed-for-sync-12345"))

        assertEquals(
            listOf(47080, 47036, 47057, 47041, 47042, 47028, 47074, 47069, 47075, 47034),
            sequence(hopper, 10),
        )
    }

    /**
     * The headline claim, stated as an assertion for the first time.
     *
     * Same seed, same range, same strategy, same hash — and the two sides do
     * not agree on a single position.
     */
    @Test
    fun `the Kotlin stream is not the Go stream`() {
        val kotlin = sequence(
            PortHopper(config(47000, 47100, HopStrategy.RANDOM, "test-seed-for-sync-12345")),
            GoReference.randomSequence.size,
        )

        assertNotEquals(
            "PortHopper.kt claims Go compatibility; if that became true, this test and the " +
                "header comment both need rewriting rather than deleting",
            GoReference.randomSequence,
            kotlin,
        )
        assertEquals(
            "the comparison is only meaningful position by position",
            GoReference.randomSequence.size,
            kotlin.size,
        )
        assertEquals(
            "not one position agrees, which is what two unrelated PRNGs look like",
            0,
            kotlin.indices.count { kotlin[it] == GoReference.randomSequence[it] },
        )
    }

    // --- 2. the reserved-port filter Kotlin does not have ---------------------

    /**
     * The divergence that does not need a PRNG to show up.
     *
     * Go walks 440..446 and steps over 443 every lap, because a hopper landing
     * there collides with the fixed default listener. Kotlin walks straight
     * through it. Sequential takes no seed, so this is a clean comparison: same
     * range, same strategy, no randomness on either side.
     */
    @Test
    fun `Kotlin hands out 443 where Go steps over it`() {
        val kotlin = sequence(
            PortHopper(config(440, 446, HopStrategy.SEQUENTIAL, seed = null)),
            GoReference.sequentialAcross443.size,
        )

        assertTrue(
            "Kotlin has no equivalent of Go's ReservedPort443 re-draw, so 443 must appear: $kotlin",
            443 in kotlin,
        )
        assertTrue(
            "the Go reference must not contain it, or the comparison proves nothing",
            443 !in GoReference.sequentialAcross443,
        )
        assertNotEquals(GoReference.sequentialAcross443, kotlin)
    }

    /**
     * And the same hole under RANDOM, where the two sides also disagree on the
     * starting point of the walk. Twelve draws over a seven-port range: if
     * Kotlin filtered 443 the way Go does, it would not show up here either.
     */
    @Test
    fun `the reserved port is reachable under the random strategy too`() {
        val kotlin = sequence(
            PortHopper(config(440, 446, HopStrategy.RANDOM, "reserved-probe")),
            40,
        )
        assertTrue("443 must be reachable: $kotlin", 443 in kotlin)
    }

    // --- 3. the properties the old file did test, kept ------------------------

    /**
     * Determinism is real and worth keeping: two hoppers built from the same
     * seed walk the same path. This is the one assertion the deleted file made
     * that could fail.
     */
    @Test
    fun `the same seed replays the same walk`() {
        val cfg = config(47000, 47050, HopStrategy.RANDOM, "deterministic-test-seed")

        assertEquals(sequence(PortHopper(cfg), 5), sequence(PortHopper(cfg), 5))
    }

    /**
     * [PortHopper.reset] documents itself as restarting the seeded sequence.
     * It reseeds the generator, so the walk after a reset must match the walk
     * from a fresh hopper — including the first port, which `reset` recomputes.
     */
    @Test
    fun `reset restarts the seeded walk`() {
        val cfg = config(47000, 47050, HopStrategy.RANDOM, "deterministic-test-seed")
        val hopper = PortHopper(cfg)

        val first = sequence(hopper, 5)
        hopper.reset()
        val afterReset = buildList {
            add(hopper.currentPort())
            repeat(4) { add(hopper.nextPort()) }
        }

        assertEquals(first, afterReset)
    }

    /**
     * An unseeded hopper must not be deterministic — that is the whole reason
     * for the SecureRandom branch. Two hoppers built from the same unseeded
     * config landing on the same first port every time would mean the branch
     * is dead.
     */
    @Test
    fun `an unseeded hopper is not deterministic`() {
        val cfg = config(47000, 65535, HopStrategy.RANDOM, seed = null)

        val firstPorts = List(20) { PortHopper(cfg).currentPort() }

        assertTrue(
            "every unseeded hopper started on the same port; the SecureRandom branch is not running",
            firstPorts.toSet().size > 1,
        )
    }
}
