package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The relationships between these timeouts used to live in a hand-maintained
 * comment ("25 second timeout (must be < CONNECTION_TIMEOUT=30s)"). Nothing
 * enforced it, and the budget as a whole was never checked against what the
 * core actually spends.
 */
class ConnectBudgetTest {

    /**
     * Reads the four numbers [ConnectBudget] mirrors out of the core's own
     * source, so "mirrored" can be asserted instead of asserted about.
     *
     * Where the checkout is: `.github/workflows/ci.yml` checks the core out
     * into `$GITHUB_WORKSPACE/tiredvpn-core` before running `./gradlew test`,
     * so walking up from the test's working directory finds it. A developer's
     * machine usually has no such directory, and the tests must not go red for
     * that — the absent case is a skip, not a failure.
     *
     * Deliberately NOT searched for: an ordinary core clone under some other
     * name. A working tree that happens to be sitting a few hundred commits
     * behind answers "what did the core say in June", which is a different
     * question from the one this test asks, and it would answer it in red. To
     * run this locally, point it at a checkout you have just fetched:
     *
     *     ./gradlew test -Dtiredvpn.core.dir=$HOME/repos/tiredvpn-oss
     */
    private object CoreSource {

        private const val REL = "internal/strategy/strategy.go"

        /** Set explicitly: a wrong path is a misconfiguration and must fail. */
        private fun explicit(): File? {
            val dir = System.getProperty("tiredvpn.core.dir")
                ?: System.getenv("TIREDVPN_CORE_DIR")
                ?: return null
            val file = File(dir, REL)
            assertTrue("tiredvpn.core.dir=$dir has no $REL", file.isFile)
            return file
        }

        private fun discovered(): File? {
            var dir: File? = File("").absoluteFile
            repeat(8) {
                val here = dir ?: return null
                val file = File(here, "tiredvpn-core/$REL")
                if (file.isFile) return file
                dir = here.parentFile
            }
            return null
        }

        fun locate(): File? = explicit() ?: discovered()

        /** The body of one Go function, up to the closing brace in column 0. */
        private fun functionBody(text: String, header: String): String {
            val start = text.indexOf(header)
            assertTrue(
                "the core no longer declares `$header`; the mirror in ConnectBudget.kt " +
                    "is describing a function that has been renamed or removed",
                start >= 0,
            )
            val rest = text.substring(start)
            val end = rest.indexOf("\n}")
            return if (end < 0) rest else rest.substring(0, end)
        }

        private fun number(text: String, pattern: String, what: String): Int {
            val match = Regex(pattern).find(text)
            assertTrue(
                "the core no longer states $what in the shape this test reads " +
                    "(/$pattern/); re-read the core and re-tune the pair by hand",
                match != null,
            )
            return match!!.groupValues[1].toInt()
        }

        fun androidProbeTimeoutSeconds(text: String): Int = number(
            functionBody(text, "func initManagerAndroidMode("),
            """m\.probeTimeout\s*=\s*(\d+)\s*\*\s*time\.Second""",
            "the Android probe timeout",
        )

        fun androidConnectTimeoutSeconds(text: String): Int = number(
            functionBody(text, "func initManagerAndroidMode("),
            """m\.connectTimeout\s*=\s*(\d+)\s*\*\s*time\.Second""",
            "the Android connect timeout",
        )

        fun silentScanAbort(text: String): Int =
            number(text, """const\s+androidSilentScanAbort\s*=\s*(\d+)""", "androidSilentScanAbort")

        fun maxEndpointAttempts(text: String): Int =
            number(text, """const\s+maxEndpointAttempts\s*=\s*(\d+)""", "maxEndpointAttempts")
    }

    /**
     * The bug. A silent address costs the core one pre-flight probe plus two
     * dead transports before it reaches a verdict, and one Connect may pay
     * that for two candidates. The old 30s budget could not cover even one.
     */
    @Test
    fun `budget covers the core's worst case connect`() {
        assertEquals(23_000L, ConnectBudget.PER_CANDIDATE_MS)
        assertEquals(46_000L, ConnectBudget.CORE_WORST_CASE_MS)

        assertTrue(
            "connect budget ${ConnectBudget.CONNECT_TIMEOUT_MS}ms does not cover " +
                "the core's ${ConnectBudget.CORE_WORST_CASE_MS}ms worst case",
            ConnectBudget.CONNECT_TIMEOUT_MS > ConnectBudget.CORE_WORST_CASE_MS
        )
        assertTrue(
            "socket read timeout ${ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS}ms cuts " +
                "the core off before its ${ConnectBudget.CORE_WORST_CASE_MS}ms worst case",
            ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS > ConnectBudget.CORE_WORST_CASE_MS
        )
    }

    /**
     * The socket read must give up first, so the failure surfaces as a real
     * error from connectToControlSocket rather than as the outer withTimeout
     * cancelling the coroutine mid-step.
     */
    @Test
    fun `socket read gives up before the overall connect does`() {
        assertTrue(
            ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS < ConnectBudget.CONNECT_TIMEOUT_MS
        )
    }

    /**
     * The old budget, kept as the explicit thing this change moves away from.
     *
     * Note what it does NOT say: one candidate at 23s did fit inside the old
     * 25s socket read, with 2s to spare. What never fit is the core's actual
     * worst case of two candidates, and in the 2026-09-01 capture even a single
     * successful attempt overran, because the pre-flight probed the v6 family
     * for 6.2s before the scan of the v4 address started. Two seconds of margin
     * is not a budget.
     */
    @Test
    fun `the previous budget could not fit the core's worst case`() {
        val oldConnectTimeout = 30_000L
        val oldSocketRead = 25_000L

        assertTrue(oldConnectTimeout < ConnectBudget.CORE_WORST_CASE_MS)
        assertTrue(oldSocketRead < ConnectBudget.CORE_WORST_CASE_MS)

        // One candidate fit, but only just — 2s of headroom over 23s.
        assertTrue(oldSocketRead > ConnectBudget.PER_CANDIDATE_MS)
        assertTrue(oldSocketRead - ConnectBudget.PER_CANDIDATE_MS < 3_000L)

        // The new budget clears a whole extra candidate.
        assertTrue(
            ConnectBudget.CONTROL_SOCKET_READ_TIMEOUT_MS - ConnectBudget.CORE_WORST_CASE_MS > 0
        )
    }

    /**
     * What the mirror is set to, with no claim that anyone checked.
     *
     * This used to be called `core constants are mirrored exactly` and its
     * comment promised it would fail if `internal/strategy/strategy.go`
     * changed. It could not: every number it compared was a literal typed into
     * the same file as the constant it was comparing against, so it asserted
     * `3_000L == 3_000L` and would have gone on passing while the core moved
     * to anything at all. The test that does the checking is below; this one
     * is a table, and says so in its name.
     *
     * It is not useless: it pins the four numbers as one group, so a change to
     * any of them shows up in a diff as a change to a documented value rather
     * than a stray edit. It just does not know anything about the core.
     */
    @Test
    fun `the mirrored core constants are recorded here, unverified`() {
        assertEquals(3_000L, ConnectBudget.CORE_PROBE_TIMEOUT_MS)
        assertEquals(10_000L, ConnectBudget.CORE_CONNECT_TIMEOUT_MS)
        assertEquals(2, ConnectBudget.CORE_SILENT_SCAN_ABORT)
        assertEquals(2, ConnectBudget.CORE_MAX_ENDPOINT_ATTEMPTS)
    }

    /**
     * The drift guard, doing the thing the old comment promised.
     *
     * Reads the four values out of the core's source and compares them with
     * [ConnectBudget]. When the core re-tunes one of them the whole derived
     * budget above is wrong — [ConnectBudget.CORE_WORST_CASE_MS] is computed
     * from all four — and this is the only place that would notice.
     *
     * Skips, rather than fails, where no core checkout is reachable. See
     * [CoreSource] for what counts as reachable and why the list is narrow.
     */
    @Test
    fun `the mirror still matches the core`() {
        val source = CoreSource.locate()
        assumeTrue(
            "no tiredvpn-core checkout next to this one; run with -Dtiredvpn.core.dir=... to check the mirror",
            source != null,
        )
        val text = source!!.readText()

        // Positive control (rule 2): a parser that silently finds nothing
        // would report agreement for every possible core.
        assertTrue("core source looks truncated: ${source.absolutePath}", text.length > 50_000)
        assertTrue(
            "the file found is not the strategy manager",
            text.contains("func initManagerAndroidMode(") && text.contains("func (m *Manager) Connect("),
        )

        assertEquals(
            "core probeTimeout (Android profile) moved",
            ConnectBudget.CORE_PROBE_TIMEOUT_MS,
            CoreSource.androidProbeTimeoutSeconds(text) * 1_000L,
        )
        assertEquals(
            "core connectTimeout (Android profile) moved",
            ConnectBudget.CORE_CONNECT_TIMEOUT_MS,
            CoreSource.androidConnectTimeoutSeconds(text) * 1_000L,
        )
        assertEquals(
            "androidSilentScanAbort moved; the service's connect budget is derived from it",
            ConnectBudget.CORE_SILENT_SCAN_ABORT,
            CoreSource.silentScanAbort(text),
        )
        assertEquals(
            "maxEndpointAttempts moved; one Connect now walks a different number of candidates",
            ConnectBudget.CORE_MAX_ENDPOINT_ATTEMPTS,
            CoreSource.maxEndpointAttempts(text),
        )
    }

    /**
     * Proves the parser above can tell the two apart.
     *
     * Without this, `the mirror still matches the core` is indistinguishable
     * from a parser that returns the expected value whatever it reads — and it
     * only runs where a core checkout exists, so most runs would never
     * exercise it at all. Here the input is written inline, so it runs
     * everywhere.
     */
    @Test
    fun `the core parser reads the numbers rather than assuming them`() {
        val fake = """
            package strategy

            const androidSilentScanAbort = 5
            const maxEndpointAttempts = 7

            func initManagerAndroidMode(m *Manager, cfg DefaultManagerConfig) {
            	m.probeTimeout = 11 * time.Second
            	m.connectTimeout = 13 * time.Second
            }
        """.trimIndent()

        assertEquals(11, CoreSource.androidProbeTimeoutSeconds(fake))
        assertEquals(13, CoreSource.androidConnectTimeoutSeconds(fake))
        assertEquals(5, CoreSource.silentScanAbort(fake))
        assertEquals(7, CoreSource.maxEndpointAttempts(fake))

        // And that it reads the Android profile, not the desktop defaults that
        // sit in NewManager above it with the same field names.
        val withDesktopDefaultsFirst = """
            func NewManager() *Manager {
            	m := &Manager{
            		probeTimeout:   10 * time.Second,
            		connectTimeout: 30 * time.Second,
            	}
            	return m
            }

            func initManagerAndroidMode(m *Manager, cfg DefaultManagerConfig) {
            	m.probeTimeout = 3 * time.Second
            	m.connectTimeout = 10 * time.Second
            }
        """.trimIndent()

        assertEquals(3, CoreSource.androidProbeTimeoutSeconds(withDesktopDefaultsFirst))
        assertNotEquals(30, CoreSource.androidConnectTimeoutSeconds(withDesktopDefaultsFirst))
    }
}
