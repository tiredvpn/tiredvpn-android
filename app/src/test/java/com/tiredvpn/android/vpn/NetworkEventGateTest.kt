package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class NetworkEventGateTest {

    @Test
    fun `the first event passes`() {
        val gate = NetworkChangeGate()
        assertTrue(gate.tryEnter(now = 100_000, connectedAt = 0, bypassDebounce = false))
    }

    @Test
    fun `a second event inside the debounce window is dropped`() {
        val gate = NetworkChangeGate(debounceMs = 3_000)
        assertTrue(gate.tryEnter(now = 100_000, connectedAt = 0, bypassDebounce = false))
        assertFalse(gate.tryEnter(now = 101_000, connectedAt = 0, bypassDebounce = false))
        assertTrue(gate.tryEnter(now = 103_000, connectedAt = 0, bypassDebounce = false))
    }

    @Test
    fun `events right after the tunnel came up are dropped`() {
        val gate = NetworkChangeGate(postConnectQuietMs = 3_000)
        assertFalse(gate.tryEnter(now = 100_500, connectedAt = 100_000, bypassDebounce = false))
        assertTrue(gate.tryEnter(now = 103_000, connectedAt = 100_000, bypassDebounce = false))
    }

    @Test
    fun `a forced event skips both quiet periods`() {
        val gate = NetworkChangeGate()
        assertTrue(gate.tryEnter(now = 100_000, connectedAt = 0, bypassDebounce = false))
        assertTrue(gate.tryEnter(now = 100_100, connectedAt = 100_000, bypassDebounce = true))
    }

    /**
     * The race the gate exists for. The ConnectivityManager callback runs on a
     * system thread and the active-network poll in a coroutine; the old code
     * read, compared and wrote `lastNetworkChangeTime` in three steps, so both
     * could pass the gate for one event and hand the core two competing TUN
     * descriptors. With the same timestamp, exactly one caller may win.
     */
    @Test
    fun `concurrent callers see exactly one winner for one event`() {
        repeat(50) {
            val gate = NetworkChangeGate()
            val winners = AtomicInteger(0)
            val start = CountDownLatch(1)
            val threads = (1..8).map {
                Thread {
                    start.await()
                    if (gate.tryEnter(now = 500_000, connectedAt = 0, bypassDebounce = true)) {
                        winners.incrementAndGet()
                    }
                }
            }
            threads.forEach { t -> t.start() }
            start.countDown()
            threads.forEach { t -> t.join() }

            assertEquals(1, winners.get())
        }
    }

    @Test
    fun `the accepted timestamp is the one that won`() {
        val gate = NetworkChangeGate()
        gate.tryEnter(now = 777_000, connectedAt = 0, bypassDebounce = true)
        assertEquals(777_000L, gate.lastAcceptedAt)
    }

    /**
     * `onLost` is registered for every validated non-VPN network, so the
     * background LTE link vanishing while Wi-Fi is alive used to clear the
     * current network and start the whole recovery machinery.
     */
    @Test
    fun `losing a network we are not using is ignored while connectivity remains`() {
        val wifi = Any()
        val lte = Any()
        assertFalse(NetworkLossPolicy.isRelevant(lost = lte, inUse = wifi, stillConnected = true))
    }

    @Test
    fun `losing the network in use is always relevant`() {
        val wifi = Any()
        assertTrue(NetworkLossPolicy.isRelevant(lost = wifi, inUse = wifi, stillConnected = true))
        assertTrue(NetworkLossPolicy.isRelevant(lost = wifi, inUse = wifi, stillConnected = false))
    }

    @Test
    fun `losing the last network is relevant even when we never tracked one`() {
        assertTrue(NetworkLossPolicy.isRelevant(lost = Any(), inUse = null, stillConnected = false))
    }

    @Test
    fun `an untracked loss with connectivity left is ignored`() {
        assertFalse(NetworkLossPolicy.isRelevant(lost = Any(), inUse = null, stillConnected = true))
    }
}
