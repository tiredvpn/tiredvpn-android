package com.tiredvpn.android.vpn

/**
 * The one clock a connect attempt is measured against.
 *
 * [ConnectBudget] says how long the whole thing may take; this says how much of
 * it is left. The distinction is the entire point, because the connect path is
 * made of blocking socket reads and a coroutine timeout does not stop one.
 *
 * What the old arrangement actually did: `withTimeout(CONNECT_TIMEOUT_MS)` - 60s
 * - wrapped a path whose steps carried their own fixed numbers, a 30s wait for
 * the control socket file and two 50s socket reads. A read entered at t=59.9s
 * ran to t≈110s, because cancelling a coroutine does not touch a socket; the
 * TimeoutCancellationException was raised only when the read returned and the
 * coroutine reached its next suspension point. The user watched "Connecting…"
 * for a minute and fifty seconds and then got the failure that had already been
 * decided at sixty.
 *
 * So every step asks [remainingMs] instead of naming its own constant, and
 * [requireMs] refuses to start one there is no budget for - a stated failure
 * beats a wait nobody is measuring.
 *
 * One deliberate sharp edge: a socket read timeout of 0 means *no timeout* in
 * java.net, so a remainder that rounded to zero would produce exactly the
 * unbounded wait this class exists to prevent. [requireMs] never returns less
 * than 1, and throws rather than return 0.
 */
internal class ConnectDeadline(
    private val startedAtMs: Long,
    val totalMs: Long = ConnectBudget.CONNECT_TIMEOUT_MS,
) {

    /** How long this attempt has been running. */
    fun elapsedMs(now: Long): Long = now - startedAtMs

    /** What is left of [totalMs], never negative. */
    fun remainingMs(now: Long): Long = (startedAtMs + totalMs - now).coerceAtLeast(0L)

    fun isExpired(now: Long): Boolean = remainingMs(now) <= 0L

    /**
     * The budget for a step that also has a ceiling of its own, e.g. the wait
     * for the control socket file to appear. Never more than either.
     */
    fun budgetMs(now: Long, ceilingMs: Long): Long = minOf(remainingMs(now), ceilingMs)

    /**
     * The budget for [step], as a number that may be handed to a socket.
     *
     * @param needMs the least this step could accomplish anything with. A
     *   retry that cannot outlast one probe is not a retry, it is a wait.
     * @throws ConnectDeadlineExceeded when there is not that much left.
     */
    fun requireMs(now: Long, step: String, needMs: Long = 1L): Long {
        val left = remainingMs(now)
        if (left < needMs.coerceAtLeast(1L)) {
            throw ConnectDeadlineExceeded(step, elapsedMs(now), totalMs, left)
        }
        return left
    }

    /** [requireMs] as the Int a socket read timeout wants. */
    fun requireSocketTimeoutMs(now: Long, step: String, needMs: Long = 1L): Int =
        requireMs(now, step, needMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * The connect budget ran out at a named step.
 *
 * Carries the step because "Connection timed out" on its own has cost real
 * diagnosis time: waiting for the core to start, waiting for it to answer
 * `connect` and waiting for it to answer `set_fd` are three different faults
 * and they were reported with one sentence.
 */
internal class ConnectDeadlineExceeded(
    val step: String,
    val elapsedMs: Long,
    val totalMs: Long,
    val remainingMs: Long,
) : Exception("connect budget spent at $step (${elapsedMs}ms of ${totalMs}ms used, ${remainingMs}ms left)")
