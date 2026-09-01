package com.tiredvpn.android.vpn

/**
 * Ownership ledger for the TUN descriptors this service created.
 *
 * Replaces the previous cleanup, which walked /proc/self/fd, matched every
 * entry whose readlink target was /dev/tun, and closed it by number via
 * ParcelFileDescriptor.adoptFd(n).close(). That sweep could not distinguish
 * our orphans from descriptors owned by someone else: the live interface the
 * service had just established, and the dup the Go core holds after receiving
 * the fd over SCM_RIGHTS both point at /dev/tun too. Closing a descriptor we
 * do not own frees the number while its real owner still believes it holds it,
 * the number is handed to the next open() in the process, and fdsan aborts on
 * the ownership mismatch. The observed victim was FileLogger, which reopened
 * its file ten times a second and so was simply the most exposed caller of
 * open(); the fault was never in the logger.
 *
 * The invariant here is narrow on purpose: this class can only ever close a
 * handle that was handed to [track], and never the one named as current. It
 * has no way to name a descriptor by number, so it cannot reach a foreign one.
 *
 * Kept free of framework types so it can be unit-tested.
 */
internal class TunHandleRegistry<T : Any>(private val closer: (T) -> Unit) {

    private val lock = Any()

    /**
     * Identity, not equality: two distinct ParcelFileDescriptor objects may
     * compare equal while owning different descriptors.
     */
    private val tracked = ArrayList<T>()

    val size: Int
        get() = synchronized(lock) { tracked.size }

    /** Record a handle we created and are responsible for closing. */
    fun track(handle: T) {
        synchronized(lock) {
            if (tracked.none { it === handle }) tracked.add(handle)
        }
    }

    /**
     * Drop a handle without closing it — for when ownership moved elsewhere or
     * something else already closed it. Prevents a later sweep from closing a
     * descriptor number that has since been reused.
     */
    fun forget(handle: T) {
        synchronized(lock) {
            tracked.removeAll { it === handle }
        }
    }

    /**
     * Close every tracked handle except [current], and forget them. [current]
     * stays tracked. Returns how many were closed.
     */
    fun releaseAllExcept(current: T?): Int {
        val doomed = synchronized(lock) {
            val out = tracked.filter { it !== current }
            tracked.removeAll { it !== current }
            out
        }
        return closeEach(doomed)
    }

    /** Close and forget everything. Returns how many were closed. */
    fun releaseAll(): Int {
        val doomed = synchronized(lock) {
            val out = ArrayList(tracked)
            tracked.clear()
            out
        }
        return closeEach(doomed)
    }

    /**
     * A failing close must not strand the rest: the handle is already out of
     * the ledger, so it is never retried and never double-closed.
     */
    private fun closeEach(handles: List<T>): Int {
        var closed = 0
        for (handle in handles) {
            try {
                closer(handle)
                closed++
            } catch (_: Throwable) {
                // already closed, or closing failed — either way it is ours no more
            }
        }
        return closed
    }
}
