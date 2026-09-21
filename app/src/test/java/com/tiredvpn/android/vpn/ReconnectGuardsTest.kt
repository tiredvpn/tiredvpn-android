package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ReconnectGuardsTest {

    @Test
    fun `a second acquire fails while the lock is held`() {
        val lock = ReconnectLock()
        val first = lock.tryAcquire()
        assertNotNull(first)
        assertNull(lock.tryAcquire())
        assertTrue(lock.isLocked)
    }

    /**
     * The exact sequence that produced two parallel reconnects against one Go
     * core: forceResetCore released the lock without a token, a new attempt
     * took it, and only then did the cancelled coroutine reach its `finally`.
     * With owner tokens the late release must fail and leave the new holder
     * alone.
     */
    @Test
    fun `a stale releaser cannot free the lock a newer holder took`() {
        val lock = ReconnectLock()
        val stale = lock.tryAcquire()!!

        assertTrue(lock.forceRelease())

        val fresh = lock.tryAcquire()
        assertNotNull(fresh)

        assertFalse("the cancelled coroutine must not release someone else's lock", lock.release(stale))
        assertTrue("the newer holder still owns the lock", lock.isLocked)
        assertNull("nobody else may acquire", lock.tryAcquire())

        assertTrue(lock.release(fresh!!))
        assertFalse(lock.isLocked)
    }

    @Test
    fun `release is idempotent for the holder`() {
        val lock = ReconnectLock()
        val token = lock.tryAcquire()!!
        assertTrue(lock.release(token))
        assertFalse("a second release must not succeed", lock.release(token))
        assertFalse(lock.isLocked)
    }

    @Test
    fun `force release on a free lock does nothing`() {
        val lock = ReconnectLock()
        assertFalse(lock.forceRelease())
        assertFalse(lock.isLocked)
        assertNotNull(lock.tryAcquire())
    }

    @Test
    fun `only one of many racing threads acquires`() {
        val lock = ReconnectLock()
        val winners = AtomicInteger(0)
        val start = CountDownLatch(1)
        val threads = (1..16).map {
            Thread {
                start.await()
                if (lock.tryAcquire() != null) winners.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, winners.get())
    }

    /**
     * The cleanup race: forceResetCore cancels the reconnect job, a fresh
     * connect() opens a new generation, and the cancelled job's
     * NonCancellable cleanup then has to recognise that it no longer owns the
     * TUN descriptor it is about to close.
     */
    @Test
    fun `cleanup from a superseded attempt is not current`() {
        val gen = ConnectGeneration()
        val old = gen.begin()
        assertTrue(gen.isCurrent(old))

        val new = gen.begin()
        assertFalse("the old attempt must not still look current", gen.isCurrent(old))
        assertTrue(gen.isCurrent(new))
    }

    /**
     * The check-then-act that a wait makes unsafe.
     *
     * The reconnect sequence runs inside its caller's NonCancellable block, so
     * cancelling it does nothing: it checks the generation, then waits seconds
     * for connectivity and a backoff, and only then connects. A connect the
     * user started during that wait would be cancelled and replaced by the
     * sequence's stale config — the user picks a different server, taps, and
     * lands back on the old one. Checking again right before the connect
     * narrows the window; claiming it in the same operation closes it.
     */
    @Test
    fun `a superseded waiter cannot claim the next generation`() {
        val gen = ConnectGeneration()
        val waiting = gen.begin()

        // The user taps connect while the sequence sleeps.
        val user = gen.begin()

        assertNull("the stale sequence must not start anything", gen.beginIfCurrent(waiting))
        assertTrue("and must not have moved the counter", gen.isCurrent(user))
    }

    @Test
    fun `an uncontested waiter claims the next generation`() {
        val gen = ConnectGeneration()
        val waiting = gen.begin()

        val claimed = gen.beginIfCurrent(waiting)
        assertEquals(waiting + 1, claimed)
        assertTrue(gen.isCurrent(claimed!!))
        assertFalse("the generation it waited on is now stale", gen.isCurrent(waiting))
    }

    /**
     * Repeated, because one round of this proves nothing.
     *
     * The interesting wrong implementation is not "always claims" — it is
     * `if (isCurrent(expected)) begin()`, a check followed by a bump, which is
     * what the production code did before and which a single round of racing
     * threads passes most of the time. Two hundred rounds of eight threads
     * released together is enough for the gap between the read and the
     * increment to be hit; an atomic compare-and-set cannot be hit at all.
     */
    @Test
    fun `only one of many racing waiters claims, over and over`() {
        repeat(200) { round ->
            val gen = ConnectGeneration()
            val from = gen.begin()
            val winners = AtomicInteger(0)
            val start = CountDownLatch(1)
            val threads = (1..8).map {
                Thread {
                    start.await()
                    if (gen.beginIfCurrent(from) != null) winners.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join() }

            assertEquals("round $round: two waiters both thought they had claimed", 1, winners.get())
            assertEquals("round $round: the counter moved more than once", from + 1, gen.current)
        }
    }

    // --- ownership of the one core in the process ---------------------------

    /**
     * The check that a generation cannot answer.
     *
     * `NativeProcessJNI.stop()` reaches `stopClient()` on a Go package-level
     * variable, so holding a reference to the old wrapper and calling stop on
     * it stops whatever core is running. An `isCurrent()` before the call is
     * check-then-act over shared state; taking the core out of the slot is not.
     */
    @Test
    fun `a teardown that does not own the core is refused`() {
        val core = CoreOwnership()
        core.claim(1)

        assertFalse("generation 2 never owned it", core.takeForTeardown(2))
        assertEquals(1, core.currentOwner)
        assertTrue(core.takeForTeardown(1))
    }

    @Test
    fun `taking for teardown empties the slot so nobody else can stop it twice`() {
        val core = CoreOwnership()
        core.claim(1)

        assertTrue(core.takeForTeardown(1))
        assertFalse("the same teardown must not run twice", core.takeForTeardown(1))
        assertEquals(CoreOwnership.NOBODY, core.currentOwner)
        assertTrue("and the slot is marked busy until it finishes", core.isTearingDown)

        core.finishTeardown(1)
        assertFalse(core.isTearingDown)
    }

    @Test
    fun `a teardown of a core nobody owns does nothing`() {
        val core = CoreOwnership()
        assertFalse(core.takeForTeardown(CoreOwnership.NOBODY))
        assertFalse(core.takeForTeardown(7))
    }

    /**
     * The other half: a connect must not start a core while the previous owner
     * is still inside its stop, because the stop it is inside reaches the
     * global one.
     */
    @Test
    fun `a connect waits for a teardown in flight and then owns the core`() {
        val core = CoreOwnership()
        core.claim(1)
        assertTrue(core.takeForTeardown(1))

        val handedOver = java.util.concurrent.CountDownLatch(1)
        val waiter = Thread {
            if (core.awaitHandover(5_000)) handedOver.countDown()
        }
        waiter.start()

        Thread.sleep(50)
        assertEquals("the waiter must still be waiting", 1, handedOver.count)

        core.finishTeardown(1)
        assertTrue("the handover must wake it", handedOver.await(5, java.util.concurrent.TimeUnit.SECONDS))
        waiter.join()

        core.claim(2)
        assertEquals(2, core.currentOwner)
    }

    /**
     * Bounded, and that is the deadlock argument: a core wedged in Go delays a
     * connect by the timeout and cannot prevent it. The caller logs and starts
     * anyway.
     *
     * The JUnit timeout is not decoration. An unbounded `await()` here does not
     * fail an assertion, it hangs — and a hung suite is a worse signal than a
     * red one, because it looks like an infrastructure problem. Five seconds is
     * forty times the wait this asks for.
     */
    @Test(timeout = 5_000)
    fun `waiting for a handover gives up rather than waiting forever`() {
        val core = CoreOwnership()
        core.claim(1)
        core.takeForTeardown(1)

        val startedAt = System.nanoTime()
        assertFalse("a teardown that never finishes must not block forever", core.awaitHandover(120))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("returned after ${elapsedMs}ms, expected at least the timeout", elapsedMs >= 100)
        assertTrue("and not much more", elapsedMs < 5_000)
    }

    @Test
    fun `an authoritative reset takes whatever is there`() {
        val core = CoreOwnership()
        core.claim(9)

        assertEquals("a disconnect answers to no generation", 9, core.takeForReset())
        assertEquals(CoreOwnership.NOBODY, core.currentOwner)
        core.finishTeardown(9)

        assertEquals("an idle slot yields NOBODY", CoreOwnership.NOBODY, core.takeForReset())
        core.finishTeardown(CoreOwnership.NOBODY)
        assertFalse("and must not leave the slot marked busy", core.isTearingDown)
    }

    /**
     * An empty slot is two situations, and collapsing them is how a forced
     * reset ran the global `stop()` and `cleanup()` alongside a teardown
     * already doing exactly that. Once somebody has taken the core, `owner` is
     * NOBODY because *they* took it — the emptiness is theirs.
     */
    @Test
    fun `a reset during someone else's teardown takes nothing`() {
        val core = CoreOwnership()
        core.claim(1)
        assertTrue(core.takeForTeardown(1))

        assertNull(
            "NOBODY with a teardown in flight means 'busy', not 'free'",
            core.takeForReset()
        )
        assertTrue("and the other teardown must keep the slot", core.isTearingDown)

        core.finishTeardown(1)
        assertEquals("once it is over, a reset may take the idle slot", CoreOwnership.NOBODY, core.takeForReset())
    }

    @Test
    fun `a reset cannot steal a core somebody is stopping, however many try`() {
        repeat(200) { round ->
            val core = CoreOwnership()
            core.claim(1)
            core.takeForTeardown(1)

            val stolen = AtomicInteger(0)
            val start = CountDownLatch(1)
            val threads = (1..8).map {
                Thread {
                    start.await()
                    if (core.takeForReset() != null) stolen.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join() }

            assertEquals("round $round: a reset joined a teardown in flight", 0, stolen.get())
        }
    }

    @Test
    fun `a stale finishTeardown does not free the slot a newer teardown holds`() {
        val core = CoreOwnership()
        core.claim(1)
        core.takeForTeardown(1)
        core.finishTeardown(1)

        core.claim(2)
        core.takeForTeardown(2)

        core.finishTeardown(1)
        assertTrue("generation 1 is long gone and may not speak for 2", core.isTearingDown)
        core.finishTeardown(2)
        assertFalse(core.isTearingDown)
    }

    /**
     * Only one teardown may hold the core, however many arrive at once.
     * Repeated, for the same reason as the generation test above: one round of
     * a race proves very little.
     */
    @Test
    fun `only one of many racing teardowns takes the core`() {
        repeat(200) { round ->
            val core = CoreOwnership()
            core.claim(1)
            val winners = AtomicInteger(0)
            val start = CountDownLatch(1)
            val threads = (1..8).map {
                Thread {
                    start.await()
                    if (core.takeForTeardown(1)) winners.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join() }

            assertEquals("round $round: two teardowns both stopped the one core", 1, winners.get())
        }
    }

    @Test
    fun `generations are unique under concurrent starts`() {
        val gen = ConnectGeneration()
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val start = CountDownLatch(1)
        val threads = (1..32).map {
            Thread {
                start.await()
                seen.add(gen.begin())
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(32, seen.size)
        assertEquals(32, gen.current)
    }
}
