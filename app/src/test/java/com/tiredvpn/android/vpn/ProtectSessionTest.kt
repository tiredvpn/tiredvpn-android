package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProtectSessionTest {

    /**
     * A connection whose reads come from [chunks]; when they run out it blocks
     * until shutdown(), like a socket with a silent peer.
     */
    private class FakeTransport(chunks: List<ByteArray>, private val failWrite: Boolean = false) : ProtectTransport {
        private val queue = ArrayDeque(chunks)
        private val woken = CountDownLatch(1)
        val closes = AtomicInteger()
        val shutdowns = AtomicInteger()
        val written = mutableListOf<Byte>()
        val readBlocked = CountDownLatch(1)

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val next = synchronized(queue) { queue.removeFirstOrNull() }
            if (next != null) {
                val n = minOf(len, next.size)
                System.arraycopy(next, 0, buf, off, n)
                return n
            }
            readBlocked.countDown()
            woken.await(5, TimeUnit.SECONDS)
            return -1
        }

        override fun write(bytes: ByteArray) {
            if (failWrite) throw IOException("broken pipe")
            written += bytes.toList()
        }

        override fun shutdown() {
            shutdowns.incrementAndGet()
            woken.countDown()
        }

        override fun close() {
            closes.incrementAndGet()
            woken.countDown()
        }
    }

    private fun le(fd: Int) = byteArrayOf(fd.toByte(), (fd shr 8).toByte(), (fd shr 16).toByte(), (fd shr 24).toByte())

    @Test
    fun `a request is protected, answered and closed once`() {
        val t = FakeTransport(listOf(le(187)))
        val seen = mutableListOf<Int>()
        val outcome = ProtectSession(t) { seen += it; true }.run()

        assertEquals(ProtectSession.Outcome.PROTECTED, outcome)
        assertEquals(listOf(187), seen)
        assertEquals(listOf<Byte>(0), t.written)
        assertEquals(1, t.closes.get())
    }

    @Test
    fun `a request split over several reads is reassembled`() {
        val t = FakeTransport(listOf(byteArrayOf(0x2C), byteArrayOf(0x01, 0, 0)))
        val seen = mutableListOf<Int>()
        ProtectSession(t) { seen += it; false }.run()

        assertEquals(listOf(300), seen)
        assertEquals("refused answers 1", listOf<Byte>(1), t.written)
        assertEquals(1, t.closes.get())
    }

    @Test
    fun `a connection without data is the core's probe and gets no answer`() {
        val t = FakeTransport(emptyList())
        val session = ProtectSession(t) { error("must not protect") }
        val result = arrayOfNulls<ProtectSession.Outcome>(1)
        val runner = Thread { result[0] = session.run() }.apply { start() }
        t.readBlocked.await(5, TimeUnit.SECONDS)
        t.shutdown() // what the read timeout amounts to: the read returns -1
        runner.join(5_000)

        // Woken by the transport itself, not by a stop: still a probe.
        assertEquals(ProtectSession.Outcome.PROBE, result[0])
        assertTrue(t.written.isEmpty())
        assertEquals(1, t.closes.get())
    }

    /**
     * The stop path. The handler sits in a blocking read; a stop must wake it
     * and must not close the descriptor itself, so the number cannot be freed
     * while the handler still holds it. The handler closes it, once.
     */
    @Test
    fun `a stop during the read wakes the session, which closes its descriptor exactly once`() {
        val t = FakeTransport(listOf(byteArrayOf(1, 2)))
        val protects = AtomicInteger()
        val session = ProtectSession(t) { protects.incrementAndGet(); true }
        val result = arrayOfNulls<ProtectSession.Outcome>(1)
        val runner = Thread { result[0] = session.run() }.apply { start() }

        t.readBlocked.await(5, TimeUnit.SECONDS)
        session.wake()
        assertEquals("wake() itself closes nothing", 0, t.closes.get())
        runner.join(5_000)

        assertEquals(ProtectSession.Outcome.SHORT_READ, result[0])
        assertEquals(0, protects.get())
        assertTrue("nothing is written after a stop", t.written.isEmpty())
        assertEquals(1, t.shutdowns.get())
        assertEquals(1, t.closes.get())
    }

    @Test
    fun `a stop that lands once the request is read protects nothing`() {
        lateinit var session: ProtectSession
        val t = object : ProtectTransport {
            var closes = 0
            var given = false
            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (given) return -1
                given = true
                System.arraycopy(byteArrayOf(5, 0, 0, 0), 0, buf, off, 4)
                session.wake() // the stop arrives between the read and protect()
                return 4
            }
            override fun write(bytes: ByteArray) = error("must not answer after a stop")
            override fun shutdown() {}
            override fun close() { closes++ }
        }
        session = ProtectSession(t) { error("must not protect after a stop") }

        assertEquals(ProtectSession.Outcome.STOPPED, session.run())
        assertEquals(1, t.closes)
    }

    @Test
    fun `a failed answer still closes the descriptor once`() {
        val t = FakeTransport(listOf(le(9)), failWrite = true)
        assertEquals(ProtectSession.Outcome.WRITE_FAILED, ProtectSession(t) { true }.run())
        assertEquals(1, t.closes.get())
    }
}
