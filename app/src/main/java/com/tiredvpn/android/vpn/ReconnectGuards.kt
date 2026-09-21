package com.tiredvpn.android.vpn

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/**
 * The reconnect mutex, with an owner.
 *
 * The bug this replaces: `forceResetCore` called `reconnectMutex.unlock()`
 * with no owner. That is legal for a lock taken by `tryLock()` without one, so
 * it silently succeeded — and then the coroutine whose Job had just been
 * cancelled still ran its own `unlock()` in a `finally`. If a new reconnect had
 * taken the lock in between, the cancelled coroutine released *its* lock, and
 * two reconnects ran at once against one Go core. The exception that would have
 * exposed it was caught and dropped.
 *
 * Here every acquisition returns a token and every release names one, so a
 * stale releaser can only fail, never free somebody else's lock.
 */
internal class ReconnectLock {

    private val mutex = Mutex()

    /** Token of the current holder, for [forceRelease]. Null when free. */
    @Volatile
    private var owner: Any? = null

    val isLocked: Boolean get() = mutex.isLocked

    /** @return a token on success, null when someone else holds the lock. */
    fun tryAcquire(): Any? {
        val token = Any()
        if (!mutex.tryLock(token)) return null
        owner = token
        return token
    }

    /**
     * Release the lock, but only if [token] is what took it.
     *
     * @return true when this call actually released the lock.
     */
    fun release(token: Any): Boolean {
        return try {
            mutex.unlock(token)
            if (owner === token) owner = null
            true
        } catch (e: IllegalStateException) {
            // Stale releaser: the lock was force-released, or a newer holder
            // has it. Either way this token is not entitled to unlock.
            false
        }
    }

    /**
     * Release whatever we recorded, for the authoritative reset that has just
     * cancelled every coroutine that could be holding it. Unlike a bare
     * `unlock()`, this cannot free a lock we never observed being taken.
     *
     * @return true when a lock was actually released.
     */
    fun forceRelease(): Boolean {
        val token = owner ?: return false
        return release(token)
    }
}

/**
 * Which connection attempt a piece of cleanup belongs to.
 *
 * `forceResetCore` cancels the pending reconnect job, but that job finishes its
 * cleanup in `withContext(NonCancellable)` — by definition it keeps running.
 * A fresh `connect()` starts meanwhile, creates a process and a TUN interface,
 * and then the old cleanup nulls the shared fields and calls
 * `tunHandles.releaseAll()`, closing the descriptor the new connection is using.
 *
 * Cleanup written against a generation runs only while that generation is still
 * current, so a superseded teardown becomes a no-op instead of a sabotage.
 */
internal class ConnectGeneration {

    private val counter = AtomicInteger(0)

    /** Current generation, for cleanup that wants to check later. */
    val current: Int get() = counter.get()

    /** Open a new generation; every older one is now stale. */
    fun begin(): Int = counter.incrementAndGet()

    fun isCurrent(generation: Int): Boolean = counter.get() == generation
}
