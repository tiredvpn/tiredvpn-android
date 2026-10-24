package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ControlChannelTest {

    /** One write as the transport saw it: payload plus the attachment in force. */
    private data class Sent(val payload: String, val attachment: FileDescriptor?)

    private class FakeTransport(
        override val input: InputStream = ByteArrayInputStream(ByteArray(0)),
    ) : ControlTransport {

        @Volatile var attached: Array<FileDescriptor>? = null
        val sent = java.util.Collections.synchronizedList(mutableListOf<Sent>())
        val timeouts = java.util.Collections.synchronizedList(mutableListOf<Int>())
        var closed = false

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray) {
                // Sleep inside the write so an unsynchronised caller would
                // have ample room to move the attachment underneath us.
                Thread.sleep(1)
                sent.add(Sent(String(b), attached?.firstOrNull()))
            }
        }

        override fun attachFds(fds: Array<FileDescriptor>?) { attached = fds }
        override fun setReadTimeoutMs(ms: Int) { timeouts.add(ms) }
        override fun close() { closed = true }
    }

    @Test
    fun `a command is written with a trailing newline`() {
        val transport = FakeTransport()
        ControlChannel(transport).send("""{"command":"status"}""")

        assertEquals(1, transport.sent.size)
        assertEquals("""{"command":"status"}""" + "\n", transport.sent[0].payload)
        assertNull(transport.sent[0].attachment)
    }

    /**
     * The descriptor must ride its own write and nothing else. Leaving the
     * attachment armed is how every later `status` poll carried another copy
     * of the TUN fd, and how the core got a reused descriptor number after the
     * interface was recreated.
     */
    @Test
    fun `an attached descriptor is cleared after the write`() {
        val transport = FakeTransport()
        val channel = ControlChannel(transport)
        val fd = FileDescriptor()

        channel.send("""{"command":"set_fd"}""", fd)
        channel.send("""{"command":"status"}""")

        assertEquals(fd, transport.sent[0].attachment)
        assertNull("the next write must not carry the descriptor", transport.sent[1].attachment)
        assertNull("nothing may stay armed on the socket", transport.attached)
    }

    @Test
    fun `a failing write still clears the attachment`() {
        val transport = object : ControlTransport {
            override val input: InputStream = ByteArrayInputStream(ByteArray(0))
            var attached: Array<FileDescriptor>? = null
            override val output: OutputStream = object : OutputStream() {
                override fun write(b: Int) = throw UnsupportedOperationException()
                override fun write(b: ByteArray): Unit = throw java.io.IOException("broken pipe")
            }
            override fun attachFds(fds: Array<FileDescriptor>?) { attached = fds }
            override fun setReadTimeoutMs(ms: Int) {}
            override fun close() {}
        }

        try {
            ControlChannel(transport).send("""{"command":"set_fd"}""", FileDescriptor())
        } catch (_: java.io.IOException) {
        }

        assertNull(transport.attached)
    }

    /**
     * The five writers with no lock. `setFileDescriptorsForSend` arms the next
     * write on the socket, whoever makes it, so the periodic `status` poll
     * could take the TUN descriptor and leave `network_changed` without one.
     * Every payload here must arrive with exactly the attachment its own
     * caller passed.
     */
    @Test
    fun `concurrent writers never take each other's descriptor`() {
        val transport = FakeTransport()
        val channel = ControlChannel(transport)

        val expected = HashMap<String, FileDescriptor?>()
        val commands = (1..12).map { i ->
            val fd = if (i % 2 == 0) FileDescriptor() else null
            val payload = """{"command":"c$i"}"""
            expected[payload + "\n"] = fd
            payload to fd
        }

        val start = CountDownLatch(1)
        val threads = commands.map { (payload, fd) ->
            Thread {
                start.await()
                channel.send(payload, fd)
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(commands.size, transport.sent.size)
        for (record in transport.sent) {
            assertEquals(
                "payload ${record.payload.trim()} carried the wrong descriptor",
                expected[record.payload],
                record.attachment,
            )
        }
        assertNull(transport.attached)
    }

    /**
     * One reader for the life of the socket.
     *
     * The positive control matters here (rule 2): the assertion below is
     * meaningless unless a second reader really does lose data, so the test
     * first demonstrates the loss on the same bytes.
     */
    @Test
    fun `a single reader keeps consecutive lines`() {
        val payload = "first\nsecond\nthird\n"

        // Positive control: the shape the old code had.
        val shared = ByteArrayInputStream(payload.toByteArray())
        val readerA = shared.bufferedReader()
        assertEquals("first", readerA.readLine())
        val readerB = shared.bufferedReader()
        assertNotEquals(
            "a second reader over one stream must lose the buffered bytes",
            "second",
            readerB.readLine(),
        )

        val channel = ControlChannel(FakeTransport(ByteArrayInputStream(payload.toByteArray())))
        assertEquals("first", channel.readLine())
        assertEquals("second", channel.readLine())
        assertEquals("third", channel.readLine())
        assertNull(channel.readLine())
    }

    /**
     * `withTimeoutOrNull(15000)` around a blocking readLine cancelled a
     * coroutine and nothing else: the socket kept its own, much longer
     * timeout, so the declared number was decoration. A declared timeout has
     * to reach the socket.
     */
    @Test
    fun `a scoped read timeout reaches the transport and is restored`() {
        val transport = FakeTransport(ByteArrayInputStream("line\n".toByteArray()))
        val channel = ControlChannel(transport)
        channel.setBaseReadTimeoutMs(50_000)
        transport.timeouts.clear()

        assertEquals("line", channel.readLine(15_000))

        assertEquals(listOf(15_000, 50_000), transport.timeouts.toList())
    }

    @Test
    fun `a timed read returns null instead of throwing on timeout`() {
        val transport = object : ControlTransport {
            override val input: InputStream = object : InputStream() {
                override fun read(): Int = throw java.net.SocketTimeoutException("Read timed out")
            }
            override val output: OutputStream = object : OutputStream() {
                override fun write(b: Int) {}
            }
            val timeouts = mutableListOf<Int>()
            override fun attachFds(fds: Array<FileDescriptor>?) {}
            override fun setReadTimeoutMs(ms: Int) { timeouts.add(ms) }
            override fun close() {}
        }
        val channel = ControlChannel(transport)
        channel.setBaseReadTimeoutMs(50_000)

        assertNull(channel.readLine(15_000))
        assertTrue("the base timeout must be restored", transport.timeouts.last() == 50_000)
    }

    @Test
    fun `closing marks the channel closed and closes the transport`() {
        val transport = FakeTransport()
        val channel = ControlChannel(transport)
        channel.close()

        assertTrue(channel.isClosed)
        assertTrue(transport.closed)
    }

    @Test
    fun `every write reaches the transport exactly once`() {
        val transport = FakeTransport()
        val channel = ControlChannel(transport)
        val count = AtomicInteger(0)
        repeat(30) {
            channel.send("""{"command":"n${count.incrementAndGet()}"}""")
        }
        assertEquals(30, transport.sent.size)
    }

    // --- request: the stop's send-and-wait ----------------------------------

    /**
     * A socket's input as the core's side writes it: bytes arrive when the
     * test feeds them, and a read honours the timeout the channel set, the
     * way LocalSocket's soTimeout does (0 waits for ever).
     */
    private class CoreSide : InputStream() {
        private val bytes = java.util.concurrent.LinkedBlockingQueue<Int>()
        @Volatile var timeoutMs = 0
        val readersWaiting = AtomicInteger()

        fun feed(line: String) = (line + "\n").toByteArray().forEach { bytes.put(it.toInt() and 0xff) }
        fun eof() = bytes.put(-1)

        override fun read(): Int {
            readersWaiting.incrementAndGet()
            try {
                val b = if (timeoutMs == 0) bytes.take()
                else bytes.poll(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: throw java.net.SocketTimeoutException("read timed out")
                if (b == -1) bytes.put(-1)
                return b
            } finally {
                readersWaiting.decrementAndGet()
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = read()
            if (first == -1) return -1
            b[off] = first.toByte()
            var n = 1
            while (n < len) {
                val next = bytes.peek() ?: break
                if (next == -1) break
                b[off + n] = bytes.take().toByte()
                n++
            }
            return n
        }
    }

    private class TimedTransport(val core: CoreSide) : ControlTransport {
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        override val input: InputStream = core
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray) { sent.add(String(b)) }
        }
        override fun attachFds(fds: Array<FileDescriptor>?) {}
        override fun setReadTimeoutMs(ms: Int) { core.timeoutMs = ms }
        override fun close() {}
    }

    private val stop = """{"command":"disconnect"}"""

    @Test
    fun `with nobody reading, a request reads its own answer`() {
        val core = CoreSide()
        val channel = ControlChannel(TimedTransport(core))
        // The answer to a status poll sent before the stop arrives first.
        core.feed("""{"status":"ok","connected":true}""")
        core.feed("""{"status":"ok"}""")

        assertEquals("""{"status":"ok"}""", channel.request(stop, 2_000, ControlSocketProtocol::isStopAnswer))
    }

    /**
     * The defect this guards: the service's event listener sits in readLine
     * on the same channel for up to the socket's base timeout. A request that
     * waited for the read lock waited that long; one that read past the lock
     * would race the listener for bytes. The answer has to come through the
     * listener's read, at once.
     */
    @Test
    fun `a request is answered through the read already in progress`() {
        val core = CoreSide()
        val transport = TimedTransport(core)
        val channel = ControlChannel(transport)

        val listenerGot = java.util.concurrent.atomic.AtomicReference<String?>()
        val listener = Thread { listenerGot.set(channel.readLine()) }.apply { isDaemon = true; start() }
        while (core.readersWaiting.get() == 0) Thread.sleep(1)

        val answerer = Thread {
            while (transport.sent.isEmpty()) Thread.sleep(1)
            core.feed("""{"status":"ok"}""")
        }.apply { isDaemon = true; start() }

        val started = System.nanoTime()
        // The listener reads with no timeout at all here, so only a hand-over
        // can answer this request before its own deadline.
        val answer = channel.request(stop, 5_000, ControlSocketProtocol::isStopAnswer)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals("""{"status":"ok"}""", answer)
        assertTrue("answered in ${tookMs}ms, not at its deadline", tookMs < 2_000)
        assertEquals(listOf(stop + "\n"), transport.sent.toList())
        listener.join(2_000)
        answerer.join(2_000)
        assertEquals("the listener still sees the line", """{"status":"ok"}""", listenerGot.get())
    }

    @Test
    fun `a request without an answer gives up at its deadline`() {
        val core = CoreSide()
        val channel = ControlChannel(TimedTransport(core))
        core.feed("""{"event":"keepalive","data":""}""")

        val started = System.nanoTime()
        assertNull(channel.request(stop, 200, ControlSocketProtocol::isStopAnswer))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("gave up after ${tookMs}ms", tookMs in 150..1_500)
    }

    @Test
    fun `a request on a connection the core closed returns at once`() {
        val core = CoreSide()
        val channel = ControlChannel(TimedTransport(core))
        core.eof()

        val started = System.nanoTime()
        assertNull(channel.request(stop, 5_000, ControlSocketProtocol::isStopAnswer))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun `a request leaves the base read timeout in place`() {
        val core = CoreSide()
        val channel = ControlChannel(TimedTransport(core))
        channel.setBaseReadTimeoutMs(15_000)
        core.feed("""{"status":"ok"}""")

        channel.request(stop, 500, ControlSocketProtocol::isStopAnswer)
        assertEquals(15_000, core.timeoutMs)
    }
}
