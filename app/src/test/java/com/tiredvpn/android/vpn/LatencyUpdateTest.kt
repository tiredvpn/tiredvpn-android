package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule behind the targeted latency write.
 *
 * Opening the server list fires one coroutine per server, and each one used to
 * call `saveServer` with the whole list. Two consequences, both routine rather
 * than exotic: measurements overwrote each other, and a probe that finished
 * after its server was deleted wrote the deleted server back from its stale
 * copy.
 */
class LatencyUpdateTest {

    private fun server(id: String, latency: Long = -1) = VpnConfig(
        id = id,
        name = id,
        serverAddress = "example.org",
        serverPort = 443,
        secret = "s",
        lastLatencyMs = latency,
    )

    private val servers = listOf(server("a"), server("b", 42), server("c"))

    @Test
    fun `only the named server changes`() {
        val updated = LatencyUpdate.apply(servers, "a", 120)
        assertNotNull(updated)
        assertEquals(3, updated!!.size)
        assertEquals(120, updated[0].lastLatencyMs)
        assertEquals("the neighbours must be untouched", 42, updated[1].lastLatencyMs)
        assertEquals(-1, updated[2].lastLatencyMs)
    }

    @Test
    fun `nothing else about the server is rewritten`() {
        val updated = LatencyUpdate.apply(servers, "b", 7)!!
        assertEquals(servers[1].copy(lastLatencyMs = 7), updated[1])
    }

    /** The resurrection bug, stated as a rule. */
    @Test
    fun `a probe for a deleted server writes nothing`() {
        assertNull(LatencyUpdate.apply(servers, "gone", 99))
        assertNull(LatencyUpdate.apply(emptyList(), "a", 99))
    }

    @Test
    fun `an unchanged value writes nothing`() {
        assertNull(LatencyUpdate.apply(servers, "b", 42))
    }

    @Test
    fun `the input list is not modified in place`() {
        val original = servers.toList()
        LatencyUpdate.apply(servers, "a", 555)
        assertEquals(original, servers)
        assertEquals(-1, servers[0].lastLatencyMs)
    }

    /**
     * Two probes landing one after the other must both survive — the whole
     * point of not rewriting the list from a stale copy.
     */
    @Test
    fun `sequential probes accumulate instead of overwriting`() {
        val afterA = LatencyUpdate.apply(servers, "a", 10)!!
        val afterC = LatencyUpdate.apply(afterA, "c", 30)!!

        assertEquals(10, afterC[0].lastLatencyMs)
        assertEquals(42, afterC[1].lastLatencyMs)
        assertEquals(30, afterC[2].lastLatencyMs)
    }
}
