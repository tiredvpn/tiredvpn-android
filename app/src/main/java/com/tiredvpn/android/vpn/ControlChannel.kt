package com.tiredvpn.android.vpn

import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream

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
    private val readLock = Any()

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
    fun readLine(): String? = synchronized(readLock) { reader.readLine() }

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
    fun readLine(timeoutMs: Int): String? = synchronized(readLock) {
        transport.setReadTimeoutMs(timeoutMs)
        try {
            reader.readLine()
        } catch (e: java.net.SocketTimeoutException) {
            null
        } finally {
            transport.setReadTimeoutMs(baseReadTimeoutMs)
        }
    }

    fun close() {
        closed = true
        try { transport.close() } catch (_: Exception) {}
    }

    val isClosed: Boolean get() = closed
}
