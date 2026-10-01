package com.tiredvpn.android.vpn

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    /**
     * Open a new generation, but only if [expected] is still the current one.
     *
     * For work that decided to connect a long time before it got round to it.
     * A reconnect sequence checks the generation, then waits for connectivity
     * and a backoff — several seconds during which the user can pick a
     * different server and tap connect. Checking again before calling
     * `connect()` narrows that window but does not close it: between the check
     * and the increment, the newer attempt can still arrive, and then the stale
     * sequence opens a generation *above* it, cancels its job and dials the old
     * config. Check and claim have to be the same operation.
     *
     * @return the new generation, or null when [expected] has been superseded
     *         and the caller has no right to start anything.
     */
    fun beginIfCurrent(expected: Int): Int? =
        if (counter.compareAndSet(expected, expected + 1)) expected + 1 else null

    fun isCurrent(generation: Int): Boolean = counter.get() == generation
}

/**
 * Set by a teardown, lifted only by a start somebody asked for.
 *
 * [ConnectGeneration] cannot express "the user turned it off": every
 * self-healing path — scheduleAutoReconnect, handleControlSocketBroken,
 * handleCoreExit — opens a generation of its own, and none of them can tell
 * whether `disconnect()` ran between its state check and its `begin()`. The
 * state flow cannot either: the failing connect overwrote Disconnected with
 * Error on the next line. A tap on Disconnect while the core was still
 * answering `connect` closed the control socket under the waiting read, the
 * attempt reported that as an ordinary failure, published Error and scheduled
 * a reconnect, and the tunnel came back with the UI already showing it off.
 *
 * So the latch is one-way for everything inside the service. `disconnect()`
 * latches it; only ACTION_CONNECT and the sticky restart of a tunnel the user
 * still wants lift it. A check that races `disconnect()` can still read it as
 * open, which is why the place a TUN comes into existence checks it again
 * afterwards — see [TunHandleRegistry.establishUnless].
 */
internal class StopLatch {

    private val latched = AtomicBoolean(false)

    val isLatched: Boolean get() = latched.get()

    fun latch() = latched.set(true)

    fun lift() = latched.set(false)
}

/**
 * Who owns the process-wide core, and the handover between them.
 *
 * [ConnectGeneration] answers "is this attempt still current", which is enough
 * for anything the attempt made for itself — its TUN descriptor, its control
 * channel. It is not enough for the core, and that is the whole reason this
 * exists: `NativeProcessJNI.stop()` calls `TiredVpnNative.stop()`, which is
 * `stopClient()` on a Go package-level variable. There is one core in the
 * process. Holding a reference to the old `NativeProcessJNI` and calling stop
 * on *that object* stops whatever core is running, including one a newer
 * attempt has just started. The protect server has the same shape: one
 * listening socket, one fixed path, reached through a field.
 *
 * Re-checking the generation before the call does not fix it, because the
 * check and the call are still two steps over shared state. So ownership is
 * taken, not verified:
 *
 *  - a teardown calls [takeForTeardown], which atomically confirms the caller
 *    owns the core *and* empties the slot. Whoever else arrives now finds
 *    nothing to stop;
 *  - a connect calls [awaitHandover] before it starts anything global, so it
 *    cannot create a core while the previous owner is still inside its stop;
 *  - [claim] publishes the new owner.
 *
 * On deadlock, since this sits in front of blocking JNI: every critical
 * section here is a handful of field assignments — no JNI, no I/O, no
 * suspension point inside the lock. The blocking stop happens between
 * [takeForTeardown] and [finishTeardown], with the lock released. The only
 * waiting is [awaitHandover], which is bounded and reports a timeout instead
 * of waiting again; the caller then proceeds, which is what it did before this
 * class existed. A wedged core can therefore delay a connect by the timeout,
 * and cannot stop one.
 */
internal class CoreOwnership {

    companion object {
        /** No generation. The core is not running, or nobody admits to it. */
        const val NOBODY = 0
    }

    private val lock = ReentrantLock()
    private val handedOver = lock.newCondition()

    private var owner: Int = NOBODY
    private var tearingDown: Int = NOBODY

    /** The generation that owns the core, for logging and tests. */
    val currentOwner: Int get() = lock.withLock { owner }

    /** True while somebody is inside a teardown. */
    val isTearingDown: Boolean get() = lock.withLock { tearingDown != NOBODY }

    /** Publish [generation] as the owner. Called just before the core starts. */
    fun claim(generation: Int) {
        lock.withLock { owner = generation }
    }

    /**
     * Take the core out of the slot, if [generation] still owns it.
     *
     * @return true when the caller may now stop it, and must call
     *         [finishTeardown] when done.
     */
    fun takeForTeardown(generation: Int): Boolean {
        lock.withLock {
            if (generation == NOBODY || owner != generation) return false
            owner = NOBODY
            tearingDown = generation
            return true
        }
    }

    /**
     * Take whatever is in the slot, for the authoritative reset that answers
     * to no generation — a user-initiated disconnect, a forced reset.
     *
     * An empty slot is two different situations and they must not be confused.
     * `owner == NOBODY` with nothing tearing down means the core is not running
     * and the caller's idempotent cleanup is harmless. `owner == NOBODY` while
     * `tearingDown` holds a generation means somebody has *already taken* the
     * core and is inside its stop — the emptiness is theirs, not an invitation.
     * Returning NOBODY for both is what let a forced reset call the global
     * `stop()` and `cleanup()` alongside a teardown already doing exactly that.
     *
     * @return the generation taken, [NOBODY] when the slot was genuinely idle,
     *         or null when another teardown owns it. Null means do nothing
     *         global: the other teardown is already stopping the same core, and
     *         doing it twice is the fault this class exists to prevent. The
     *         caller must pass a non-null result to [finishTeardown].
     */
    fun takeForReset(): Int? {
        lock.withLock {
            if (tearingDown != NOBODY) return null
            val taken = owner
            owner = NOBODY
            if (taken != NOBODY) tearingDown = taken
            return taken
        }
    }

    /** The teardown is over; anyone waiting for the handover may proceed. */
    fun finishTeardown(generation: Int) {
        lock.withLock {
            if (generation != NOBODY && tearingDown == generation) {
                tearingDown = NOBODY
                handedOver.signalAll()
            }
        }
    }

    /**
     * Wait for any teardown in flight to finish, for at most [timeoutMs].
     *
     * @return true when the slot is free, false on timeout — in which case the
     *         caller proceeds anyway and says so in the log. The alternative,
     *         waiting until the previous owner returns, turns a core wedged in
     *         Go into a connect that never starts.
     */
    fun awaitHandover(timeoutMs: Long): Boolean {
        lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (tearingDown != NOBODY) {
                if (remaining <= 0L) return false
                remaining = handedOver.awaitNanos(remaining)
            }
            return true
        }
    }
}
