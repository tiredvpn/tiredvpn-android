package com.tiredvpn.android.vpn

import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The bits of a control socket [ControlChannel] needs, named so the channel can
 * be exercised without an Android LocalSocket.
 */
internal interface ControlTransport {
    val input: InputStream
    val output: OutputStream

    /**
     * Attach descriptors to the NEXT write, or clear a pending attachment when
     * [fds] is null. Mirrors LocalSocket.setFileDescriptorsForSend, including
     * its one dangerous property: the attachment is socket-wide state, not an
     * argument of a write.
     */
    fun attachFds(fds: Array<FileDescriptor>?)

    /** Blocking read timeout for subsequent reads, in milliseconds. */
    fun setReadTimeoutMs(ms: Int)

    fun close()
}

/**
 * The one way to talk to the core over the control socket.
 *
 * Three separate bugs made this a class instead of five call sites:
 *
 *  - **Five writers, no lock.** setFileDescriptorsForSend arms the NEXT write
 *    on the socket, whoever makes it. The periodic `status` poll and
 *    `network_available` could slip between arming and `network_changed`, take
 *    the TUN descriptor with them, and leave the command that needed it
 *    without one. [send] makes arm-write-disarm one critical section.
 *
 *  - **The attachment was never cleared.** `setFileDescriptorsForSend(null)`
 *    appeared nowhere, so every later write carried another copy of the TUN
 *    descriptor. Once the interface was recreated the number got reused, and
 *    the core received a descriptor belonging to something else. [send]
 *    clears in a finally, inside the same critical section.
 *
 *  - **Three BufferedReaders over one stream.** connectToControlSocket,
 *    sendTunFd and startStatusMonitoring each wrapped
 *    `socket.inputStream`. The first pulls up to 8 KB into its own buffer; for
 *    the next reader those bytes are simply gone. A `set_fd` answer swallowed
 *    that way turned into a 50-second wait, a false "Failed to activate
 *    tunnel" and a needless interface rebuild. There is exactly one reader
 *    here and it lives as long as the socket.
 */
internal class ControlChannel(private val transport: ControlTransport) {

    private val writeLock = Any()
    private val readLock = ReentrantLock()

    /** A [request] waiting for its answer; see [offer]. */
    private class Waiter(val isAnswer: (String) -> Boolean) {
        val done = CountDownLatch(1)
        @Volatile var line: String? = null
    }

    private val waiters = CopyOnWriteArrayList<Waiter>()

    /** The single reader. Recreated only by recreating the channel. */
    private val reader = transport.input.bufferedReader()

    @Volatile
    private var closed = false

    /** Read timeout the socket sits at between explicit overrides. */
    @Volatile
    private var baseReadTimeoutMs: Int = 0

    /**
     * Set the timeout that [readLine] restores after a scoped override, and
     * apply it now.
     */
    fun setBaseReadTimeoutMs(ms: Int) {
        baseReadTimeoutMs = ms
        transport.setReadTimeoutMs(ms)
    }

    /**
     * Write one newline-terminated command, optionally handing [fd] to the
     * core over SCM_RIGHTS. Serialized against every other writer.
     */
    fun send(json: String, fd: FileDescriptor? = null) {
        synchronized(writeLock) {
            if (fd != null) transport.attachFds(arrayOf(fd))
            try {
                val out = transport.output
                out.write((json + "\n").toByteArray())
                out.flush()
            } finally {
                // Unconditional: an exception from write() still leaves the
                // attachment armed for whoever writes next.
                if (fd != null) transport.attachFds(null)
            }
        }
    }

    /**
     * Read one line, blocking for at most the socket's current read timeout.
     */
    fun readLine(): String? = readLock.withLock { reader.readLine().also(::offer) }

    /**
     * Read one line with [timeoutMs] as the real deadline.
     *
     * A coroutine `withTimeoutOrNull` around a blocking `readLine()` does not
     * do this: cancelling a coroutine does not touch the socket, so the read
     * runs to the socket's own timeout and the declared number is decoration.
     * Here the number is the socket option, so it is the truth.
     *
     * @return the line, or null on timeout.
     */
    fun readLine(timeoutMs: Int): String? = readLock.withLock {
        transport.setReadTimeoutMs(timeoutMs)
        try {
            reader.readLine().also(::offer)
        } catch (e: java.net.SocketTimeoutException) {
            null
        } finally {
            transport.setReadTimeoutMs(baseReadTimeoutMs)
        }
    }

    /**
     * Send [json] and wait at most [timeoutMs] for a line [isAnswer] accepts.
     *
     * The channel has one reader, and while a tunnel is up it is the service's
     * event listener, parked in [readLine] for up to the socket's base timeout.
     * Reading here as well would wait on that read's lock for as long. So a
     * line read by anyone is offered to the requests waiting for one, and a
     * request reads for itself only when nobody else is reading.
     *
     * Lines this call reads itself and does not accept are dropped. It is for
     * the last exchange on a channel - the stop - where nothing is left to
     * hand them to.
     *
     * @return the answer, or null on timeout or when the core closed the
     *   connection.
     */
    fun request(json: String, timeoutMs: Long, isAnswer: (String) -> Boolean): String? {
        val waiter = Waiter(isAnswer)
        waiters.add(waiter)
        try {
            send(json)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                if (waiter.done.count == 0L) return waiter.line
                val leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (leftMs <= 0) return null
                if (readLock.tryLock()) {
                    try {
                        transport.setReadTimeoutMs(leftMs.toInt().coerceAtLeast(1))
                        val line = try {
                            reader.readLine() ?: return waiter.line
                        } catch (e: java.net.SocketTimeoutException) {
                            null
                        } finally {
                            transport.setReadTimeoutMs(baseReadTimeoutMs)
                        }
                        offer(line)
                    } finally {
                        readLock.unlock()
                    }
                } else {
                    waiter.done.await(minOf(leftMs, REQUEST_POLL_MS), TimeUnit.MILLISECONDS)
                }
            }
        } finally {
            waiters.remove(waiter)
        }
    }

    /** Hand [line] to every waiting [request] that accepts it. */
    private fun offer(line: String?) {
        if (line == null) return
        for (w in waiters) {
            if (w.done.count != 0L && w.isAnswer(line)) {
                w.line = line
                w.done.countDown()
            }
        }
    }

    fun close() {
        closed = true
        try { transport.close() } catch (_: Exception) {}
    }

    val isClosed: Boolean get() = closed

    private companion object {
        /**
         * How often a waiting [request] looks again at whether the reader
         * went away. Its answer itself arrives through [offer] at once.
         */
        const val REQUEST_POLL_MS = 20L
    }
}
