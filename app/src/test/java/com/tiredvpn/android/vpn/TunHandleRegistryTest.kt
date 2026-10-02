package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunHandleRegistryTest {

    /**
     * Stands in for a ParcelFileDescriptor. equals/hashCode deliberately
     * collapse every instance onto one value, mirroring the hazard that two
     * distinct descriptors can compare equal: the registry must key on
     * identity, not equality.
     */
    private class Handle(val name: String) {
        var closeCount = 0
        override fun equals(other: Any?) = other is Handle
        override fun hashCode() = 42
        override fun toString() = name
    }

    private fun registry(): Pair<TunHandleRegistry<Handle>, MutableList<Handle>> {
        val closed = mutableListOf<Handle>()
        val reg = TunHandleRegistry<Handle> { it.closeCount++; closed.add(it) }
        return reg to closed
    }

    /**
     * The crash invariant. The old sweep closed every /dev/tun descriptor it
     * found, including the interface that had just been established; the owner
     * still held it, the number got reused, and fdsan aborted the process.
     */
    @Test
    fun `current handle is never closed`() {
        val (reg, closed) = registry()
        val old = Handle("old")
        val current = Handle("current")
        reg.track(old)
        reg.track(current)

        val n = reg.releaseAllExcept(current)

        assertEquals(1, n)
        assertTrue(closed.contains(old))
        assertFalse(closed.any { it === current })
        assertEquals(0, current.closeCount)
    }

    /**
     * The other half of the crash: descriptors the registry was never given —
     * the Go core's dup received over SCM_RIGHTS — must be unreachable. The
     * registry has no API taking a raw number, so the test asserts the sweep
     * touches nothing when nothing was tracked.
     */
    @Test
    fun `untracked handles are never closed`() {
        val (reg, closed) = registry()
        val foreign = Handle("core-dup")

        assertEquals(0, reg.releaseAllExcept(null))
        assertEquals(0, reg.releaseAll())

        assertTrue(closed.isEmpty())
        assertEquals(0, foreign.closeCount)
    }

    /** Repeated sweeps must not close the same descriptor twice. */
    @Test
    fun `repeated sweeps do not double close`() {
        val (reg, _) = registry()
        val orphan = Handle("orphan")
        reg.track(orphan)

        assertEquals(1, reg.releaseAllExcept(null))
        assertEquals(0, reg.releaseAllExcept(null))
        assertEquals(0, reg.releaseAll())

        assertEquals(1, orphan.closeCount)
    }

    /** The delayed sweep runs ten times; the current handle survives all of them. */
    @Test
    fun `current handle survives repeated sweeps`() {
        val (reg, _) = registry()
        val current = Handle("current")
        reg.track(current)

        repeat(10) { reg.releaseAllExcept(current) }

        assertEquals(0, current.closeCount)
        assertEquals(1, reg.size)
    }

    /** forget() drops ownership so a later sweep cannot reach a reused number. */
    @Test
    fun `forgotten handle is not closed`() {
        val (reg, closed) = registry()
        val handed = Handle("handed-off")
        reg.track(handed)

        reg.forget(handed)
        assertEquals(0, reg.releaseAll())

        assertTrue(closed.isEmpty())
        assertEquals(0, handed.closeCount)
    }

    /** track() must be idempotent, or one descriptor gets closed twice. */
    @Test
    fun `tracking the same handle twice closes it once`() {
        val (reg, _) = registry()
        val h = Handle("dup")
        reg.track(h)
        reg.track(h)

        assertEquals(1, reg.size)
        assertEquals(1, reg.releaseAll())
        assertEquals(1, h.closeCount)
    }

    /** A throwing close must not strand the remaining orphans. */
    @Test
    fun `a failing close does not strand the rest`() {
        val closed = mutableListOf<Handle>()
        val reg = TunHandleRegistry<Handle> {
            if (it.name == "bad") throw IllegalStateException("already closed")
            it.closeCount++
            closed.add(it)
        }
        val bad = Handle("bad")
        val good = Handle("good")
        reg.track(bad)
        reg.track(good)

        reg.releaseAll()

        assertEquals(1, good.closeCount)
        assertEquals(0, reg.size)
    }

    /** Concurrent sweeps must close each descriptor exactly once. */
    @Test
    fun `concurrent sweeps close each handle once`() {
        val (reg, _) = registry()
        val handles = (1..200).map { Handle("h$it") }
        handles.forEach { reg.track(it) }

        val threads = (1..8).map { Thread { reg.releaseAll() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(handles.all { it.closeCount == 1 })
        assertEquals(0, reg.size)
    }

    // --- establishUnless: a stop racing establish() ---------------------------

    @Test
    fun `a stopped tunnel does not establish at all`() {
        val (reg, _) = registry()
        var created = 0

        val got = reg.establishUnless(refused = { true }) { created++; Handle("never") }

        assertEquals(null, got)
        assertEquals("establish() must not even be called after a stop", 0, created)
        assertEquals(0, reg.size)
    }

    @Test
    fun `an open tunnel establishes and tracks the handle`() {
        val (reg, closed) = registry()

        val got = reg.establishUnless(refused = { false }) { Handle("tun") }

        assertTrue(got != null)
        assertEquals(1, reg.size)
        assertTrue(closed.isEmpty())
    }

    /**
     * The window the fix is for: the stop lands while establish() is running,
     * after the teardown has already swept the ledger. Nobody else will ever
     * close this handle, so establishUnless has to.
     */
    @Test
    fun `a stop that lands during establish closes the new handle and forgets it`() {
        val (reg, closed) = registry()
        var stopped = false
        val tun = Handle("late")

        val got = reg.establishUnless(refused = { stopped }) {
            stopped = true          // disconnect() latched...
            reg.releaseAll()        // ...and swept a ledger that did not hold us yet
            tun
        }

        assertEquals(null, got)
        assertEquals(1, tun.closeCount)
        assertTrue(closed.any { it === tun })
        assertEquals(0, reg.size)
    }

    /**
     * The other order: the handle was tracked before the teardown swept, so
     * the sweep closed it. Closing it again here would be the double close
     * the ledger exists to prevent.
     */
    @Test
    fun `a handle the teardown already swept is not closed a second time`() {
        val (reg, _) = registry()
        val tun = Handle("swept")
        var checks = 0

        val got = reg.establishUnless(refused = {
            checks++
            if (checks == 2) {
                reg.releaseAll() // the sweep ran between track() and the re-check
                true
            } else false
        }) { tun }

        assertEquals(null, got)
        assertEquals("closed by the sweep, exactly once", 1, tun.closeCount)
        assertEquals(0, reg.size)
    }

    // --- handOver: one interface left after every successful handover ---------

    /**
     * The leak this exists for: every reconnect that ended in a new interface
     * tracked one more handle and released none, so after N handovers the
     * ledger held N+1 open interfaces.
     */
    @Test
    fun `after N handovers exactly one handle is left and every earlier one is closed once`() {
        val (reg, _) = registry()
        val handles = (0..6).map { Handle("tun$it") }

        reg.handOver(handles[0])
        for (i in 1 until handles.size) {
            reg.track(handles[i])            // establishVpn tracks the new interface
            val closed = reg.handOver(handles[i])
            assertEquals("handover $i closes exactly the previous interface", 1, closed)
        }

        assertEquals(1, reg.size)
        assertEquals("the current interface is never closed", 0, handles.last().closeCount)
        assertTrue("every earlier interface closed exactly once", handles.dropLast(1).all { it.closeCount == 1 })
    }

    @Test
    fun `a handover also closes interfaces an earlier attempt left behind`() {
        val (reg, _) = registry()
        val orphans = listOf(Handle("a"), Handle("b"))
        orphans.forEach { reg.track(it) }
        val current = Handle("current")

        val closed = reg.handOver(current)   // not tracked yet: handOver tracks it

        assertEquals(2, closed)
        assertEquals(1, reg.size)
        assertEquals(0, current.closeCount)
        assertTrue(orphans.all { it.closeCount == 1 })
    }

    @Test
    fun `a handle a teardown already took is not closed again by a handover`() {
        val (reg, _) = registry()
        val old = Handle("old")
        reg.track(old)
        reg.releaseAll()                      // disconnect swept it
        val current = Handle("current")

        reg.handOver(current)

        assertEquals("closed by the sweep only", 1, old.closeCount)
        assertEquals(1, reg.size)
    }
}
