package com.tiredvpn.android.vpn

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The connection a protect request arrives on: a LocalSocket in the service,
 * a fake in tests.
 */
internal interface ProtectTransport {
    /**
     * Blocking read. -1 on EOF, after [shutdown], and when the socket's read
     * timeout fires; an IOException counts the same.
     */
    fun read(buf: ByteArray, off: Int, len: Int): Int

    fun write(bytes: ByteArray)

    /**
     * Wake a read or write blocked on this connection without releasing the
     * descriptor. Idempotent, safe from any thread.
     */
    fun shutdown()

    fun close()
}

/**
 * One protect request, protocol 1: 4 bytes of little-endian socket number in,
 * one byte out (`0` protected, `1` refused).
 *
 * The descriptor has exactly one owner, this session, and is closed in one
 * place, the `finally` of [run]. Anything else that needs the session to end
 * (a server stop) calls [wake], which shuts the connection down and closes
 * nothing. Closing it from the outside, as the service used to do from the
 * stop path and from a pre-API-29 timeout coroutine, freed the number while
 * the handler could still be about to read from or write to it; whatever
 * opened a descriptor next could get that number, and the handler's bytes
 * along with it. close() also does not wake a read that is already blocked,
 * so the timeout coroutine never actually freed the handler it was meant to.
 */
internal class ProtectSession(
    private val transport: ProtectTransport,
    private val protect: (Int) -> Boolean,
) {

    enum class Outcome {
        /** protect() returned true and the answer was written. */
        PROTECTED,

        /** protect() returned false and the answer was written. */
        REFUSED,

        /** Connect and close without a byte: the core's liveness probe. */
        PROBE,

        /** EOF, timeout or a wake in the middle of the 4 bytes. */
        SHORT_READ,

        /** Woken by a stop before protect() was called; nothing was protected. */
        STOPPED,

        /** protect() ran but the answer could not be written. */
        WRITE_FAILED,
    }

    private val closed = AtomicBoolean(false)

    @Volatile
    private var woken = false

    /** The socket number the request named, for the log; -1 until read. */
    @Volatile
    var requestedFd: Int = -1
        private set

    /** Serve the request. Returns once the descriptor has been closed. */
    fun run(): Outcome {
        try {
            val request = ByteArray(REQUEST_BYTES)
            var got = 0
            while (got < REQUEST_BYTES) {
                val n = try {
                    transport.read(request, got, REQUEST_BYTES - got)
                } catch (e: IOException) {
                    -1
                }
                if (n <= 0) return if (got == 0 && !woken) Outcome.PROBE else Outcome.SHORT_READ
                got += n
            }
            // A stop that landed while the request was in flight: the core is
            // going away and so is the socket it asked about.
            if (woken) return Outcome.STOPPED

            val fd = (request[0].toInt() and 0xFF) or
                ((request[1].toInt() and 0xFF) shl 8) or
                ((request[2].toInt() and 0xFF) shl 16) or
                ((request[3].toInt() and 0xFF) shl 24)
            requestedFd = fd
            val ok = protect(fd)
            return try {
                transport.write(byteArrayOf(if (ok) 0 else 1))
                if (ok) Outcome.PROTECTED else Outcome.REFUSED
            } catch (e: IOException) {
                Outcome.WRITE_FAILED
            }
        } finally {
            if (closed.compareAndSet(false, true)) {
                try { transport.close() } catch (_: Exception) {}
            }
        }
    }

    /** End [run] from another thread. Never closes the descriptor. */
    fun wake() {
        woken = true
        try { transport.shutdown() } catch (_: Exception) {}
    }

    private companion object {
        const val REQUEST_BYTES = 4
    }
}
