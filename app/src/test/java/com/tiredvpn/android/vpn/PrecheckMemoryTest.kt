package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reconnect pre-check with a dead first server. Asking only the active
 * server cost about 12 s on every full reconnect on the Pixel (three 3 s
 * probes at 10.255.255.1:995 and the waits between them).
 */
class PrecheckMemoryTest {

    private val dead = "10.255.255.1:995"
    private val usa1 = "130.94.90.203:995"
    private val usa2 = "130.94.90.203:996"
    private val pool = listOf(dead, usa1, usa2)

    @Test
    fun `the first server is asked while nothing has failed`() {
        assertEquals(dead, PrecheckMemory().pick(pool, nowMs = 0))
    }

    @Test
    fun `a server that failed is passed over for the window`() {
        val memory = PrecheckMemory(windowMs = 120_000)
        memory.failed(dead, nowMs = 1_000)
        assertEquals(usa1, memory.pick(pool, nowMs = 2_000))
        assertEquals("still inside the window at its edge", usa1, memory.pick(pool, nowMs = 121_000))
        assertEquals("and asked again once it ran out", dead, memory.pick(pool, nowMs = 121_001))
    }

    @Test
    fun `an answer clears the failure`() {
        val memory = PrecheckMemory()
        memory.failed(dead, nowMs = 1_000)
        memory.answered(dead)
        assertEquals(dead, memory.pick(pool, nowMs = 2_000))
    }

    /** A network that is really down: one probe per attempt, as before. */
    @Test
    fun `with every server failed, the one that failed longest ago is asked`() {
        val memory = PrecheckMemory()
        memory.failed(usa1, nowMs = 1_000)
        memory.failed(dead, nowMs = 2_000)
        memory.failed(usa2, nowMs = 3_000)
        assertEquals(usa1, memory.pick(pool, nowMs = 4_000))
    }

    @Test
    fun `no servers, nothing to ask`() {
        assertNull(PrecheckMemory().pick(emptyList(), nowMs = 0))
    }
}
