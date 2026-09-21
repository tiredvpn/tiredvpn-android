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
