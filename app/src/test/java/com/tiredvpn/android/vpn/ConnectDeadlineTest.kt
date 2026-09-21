package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * One clock for a connect attempt, and the connect path actually reading it.
 *
 * The defect this guards: a 60s coroutine fence around steps that carried
 * fixed numbers of their own - 30s for the control socket file, 50s per socket
 * read, twice. Cancelling a coroutine does not touch a socket, so a read
 * entered just before the fence fired ran to its own timeout and the user sat
 * on "Connecting…" for about 110 seconds before being told about a failure
 * that had been decided at sixty.
 *
 * Both halves are checked. The arithmetic here, and that the path asks for it
 * at the three places where a read can block - because arithmetic nobody calls
 * is the exact shape the old code had.
 */
class ConnectDeadlineTest {

    private val start = 1_000_000L

    private fun deadline(totalMs: Long = 60_000L) = ConnectDeadline(start, totalMs)

    // --- the clock ---

    @Test
    fun `remaining shrinks with elapsed time`() {
        val budget = deadline()

        assertEquals(60_000L, budget.remainingMs(start))
        assertEquals(45_000L, budget.remainingMs(start + 15_000L))
        assertEquals(1L, budget.remainingMs(start + 59_999L))
    }

    @Test
    fun `remaining never goes negative`() {
        val budget = deadline()

        assertEquals(0L, budget.remainingMs(start + 60_000L))
        assertEquals(0L, budget.remainingMs(start + 500_000L))
        assertTrue(budget.isExpired(start + 60_000L))
        assertFalse(budget.isExpired(start + 59_999L))
    }

    @Test
    fun `a step ceiling and the remaining budget are both respected`() {
        val budget = deadline()

        // Early: the step's own ceiling is the binding one.
        assertEquals(30_000L, budget.budgetMs(start, 30_000L))
        // Late: what is left of the attempt is.
        assertEquals(10_000L, budget.budgetMs(start + 50_000L, 30_000L))
    }

    @Test
    fun `a spent budget is refused rather than turned into an unbounded wait`() {
        val budget = deadline()

        try {
            budget.requireMs(start + 60_000L, "server handshake")
            fail("a step with no budget left must not be started")
        } catch (e: ConnectDeadlineExceeded) {
            assertEquals("server handshake", e.step)
            assertEquals(60_000L, e.elapsedMs)
            assertEquals(60_000L, e.totalMs)
            assertEquals(0L, e.remainingMs)
        }
    }

    @Test
    fun `a socket timeout is never zero, because zero means forever`() {
        val budget = deadline()

        // One millisecond left is a legal socket timeout; zero is not, it is
        // java.net's spelling of "block until something arrives".
        assertEquals(1, budget.requireSocketTimeoutMs(start + 59_999L, "server handshake"))
        try {
            budget.requireSocketTimeoutMs(start + 60_000L, "server handshake")
            fail("zero remaining must throw, not be handed to a socket")
        } catch (e: ConnectDeadlineExceeded) {
            assertEquals(0L, e.remainingMs)
        }
    }

    @Test
    fun `a step that needs a minimum is refused before it is started`() {
        val budget = deadline()

        // 8s left, and this step cannot reach a verdict in less than 13s.
        try {
            budget.requireMs(start + 52_000L, "server handshake", needMs = 13_000L)
            fail("a step that cannot finish inside the remainder must not start")
        } catch (e: ConnectDeadlineExceeded) {
            assertEquals(8_000L, e.remainingMs)
        }
        // 14s left: it fits, and the whole remainder is handed over.
        assertEquals(14_000L, budget.requireMs(start + 46_000L, "server handshake", needMs = 13_000L))
    }

    // --- the budget the path is actually built on ---

    @Test
    fun `the retry minimum is one probe plus one transport attempt`() {
        assertEquals(
            ConnectBudget.CORE_PROBE_TIMEOUT_MS + ConnectBudget.CORE_CONNECT_TIMEOUT_MS,
            ConnectBudget.HANDSHAKE_RETRY_MIN_BUDGET_MS,
        )
    }

    @Test
    fun `the socket ceiling alone cannot cover the path, which is why the deadline exists`() {
        // Two set_fd reads at the socket's own ceiling, plus the wait for the
        // socket file, come to more than the whole attempt is allowed. The old
        // code let exactly that happen.
        val unbounded = 30_000L + 2 * ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS
        assertTrue(
            "if this ever stops holding, the deadline is no longer load-bearing and this " +
                "test should be revisited rather than deleted",
            unbounded > ConnectBudget.CONNECT_TIMEOUT_MS,
        )
    }

    // --- the call sites ---

    private fun service(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(
            requireNotNull(dir),
            "src/main/java/com/tiredvpn/android/vpn/TiredVpnService.kt",
        ).readText()
    }

    @Test
    fun `every blocking read on the connect path takes its timeout from the deadline`() {
        val text = service()

        assertFalse(
            "the wait for the connect answer used the socket's standing timeout, so it could " +
                "outlive the attempt by the whole of it",
            text.contains("channel.readLine()\n") ||
                Regex("""val line = channel\.readLine\(\)""").containsMatchIn(text),
        )
        assertFalse(
            "set_fd named a constant instead of asking what was left",
            Regex("""channel\.readLine\(SET_FD_READ_TIMEOUT\)""").containsMatchIn(text),
        )
        assertTrue(
            "the connect answer must be read against the remaining budget",
            text.contains("budget.requireSocketTimeoutMs(System.currentTimeMillis(), STEP_CORE_CONNECT)"),
        )
        assertTrue(
            "set_fd must be read against the remaining budget",
            text.contains("budget.requireSocketTimeoutMs(System.currentTimeMillis(), STEP_HANDSHAKE)"),
        )
        assertTrue(
            "the wait for the control socket file must be capped by the remaining budget too",
            text.contains("budget.budgetMs(System.currentTimeMillis(), CONTROL_SOCKET_TIMEOUT.toLong())"),
        )
    }

    @Test
    fun `the deadline is started from the same number as the coroutine fence`() {
        val text = service()

        assertTrue(
            "one attempt, one origin, and the same total as withTimeout - a fence and a budget " +
                "that disagree are two answers to the same question",
            Regex("""ConnectDeadline\(System\.currentTimeMillis\(\), CONNECTION_TIMEOUT\)""")
                .containsMatchIn(text),
        )
        assertTrue(
            "withTimeout must still be there: it is what stops the cancellable waits",
            text.contains("withTimeout(CONNECTION_TIMEOUT)"),
        )
    }

    @Test
    fun `a spent budget is reported, not swallowed by the per-step catch`() {
        val text = service()

        // Three: the two steps that rethrow, and the coroutine that reports.
        // connectToControlSocket and sendTunFd both end in
        // `catch (e: Exception) { ... null }`, so without an explicit rethrow
        // ahead of it the verdict turns back into an unexplained failure.
        assertEquals(
            "one rethrow in connectToControlSocket, one in sendTunFd, one report in connect()",
            3,
            Regex("""catch \(e: ConnectDeadlineExceeded\)""").findAll(text).count(),
        )
        assertTrue(
            "the reporting branch must name the step",
            text.contains("R.string.connect_timed_out_at, e.step"),
        )
    }

    @Test
    fun `the handshake retry is skipped when it cannot finish in time`() {
        val text = service()

        assertTrue(
            "retrying with less budget than one probe plus one attempt only moves the same " +
                "failure later, which is the defect",
            text.contains("ConnectBudget.HANDSHAKE_RETRY_MIN_BUDGET_MS"),
        )
    }
}
