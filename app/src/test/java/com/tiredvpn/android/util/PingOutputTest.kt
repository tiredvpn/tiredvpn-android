package com.tiredvpn.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the number next to a server in the list actually comes from.
 *
 * [PingManager.parseIcmpLatencyMs] reads the output of `/system/bin/ping`, and
 * it had no test because it used to sit inside the loop that reads the
 * process. Two of the tests below record behaviour that is wrong rather than
 * right; they are here so a fix is a deliberate edit to this file and not a
 * surprise.
 */
class PingOutputTest {

    private fun parse(vararg lines: String): Long? =
        PingManager.parseIcmpLatencyMs(lines.asSequence())

    /** What Android's ping actually prints. */
    private val androidOutput = arrayOf(
        "PING 31.44.3.165 (31.44.3.165) 56(84) bytes of data.",
        "64 bytes from 31.44.3.165: icmp_seq=1 ttl=54 time=42.3 ms",
        "",
        "--- 31.44.3.165 ping statistics ---",
        "1 packets transmitted, 1 received, 0% packet loss, time 0ms",
        "rtt min/avg/max/mdev = 42.312/42.312/42.312/0.000 ms",
    )

    @Test
    fun `a normal reply gives its round trip time`() {
        assertEquals(42L, parse(*androidOutput))
    }

    @Test
    fun `an unreachable host says nothing at all`() {
        assertEquals(
            null,
            parse(
                "PING 10.99.99.99 (10.99.99.99) 56(84) bytes of data.",
                "",
                "--- 10.99.99.99 ping statistics ---",
                "1 packets transmitted, 0 received, 100% packet loss, time 0ms",
            ),
        )
    }

    @Test
    fun `no output at all says nothing`() {
        assertNull(parse())
    }

    /**
     * The first line mentioning a time wins, even when later lines carry
     * better ones. The statistics line at the bottom holds the same figure to
     * three decimals and is never read.
     */
    @Test
    fun `the first timed line decides`() {
        assertEquals(
            11L,
            parse(
                "64 bytes from h: icmp_seq=1 ttl=54 time=11.1 ms",
                "64 bytes from h: icmp_seq=2 ttl=54 time=99.9 ms",
            ),
        )
    }

    // --- rounding ------------------------------------------------------------

    /**
     * Truncation, not rounding: 42.9 ms is reported as 42. Harmless for a
     * list of servers, but it means the displayed figure is always at or below
     * the real one.
     */
    @Test
    fun `fractions are truncated rather than rounded`() {
        assertEquals(42L, parse("64 bytes from h: icmp_seq=1 ttl=54 time=42.9 ms"))
        assertEquals(43L, parse("64 bytes from h: icmp_seq=1 ttl=54 time=43.0 ms"))
    }

    /**
     * A loopback or a local server answers in well under a millisecond and is
     * shown as 0, which the UI has to be able to tell apart from "not
     * measured" — the sentinel for that is -1, not 0.
     */
    @Test
    fun `a sub-millisecond reply is zero, not the failure sentinel`() {
        assertEquals(0L, parse("64 bytes from h: icmp_seq=1 ttl=64 time=0.089 ms"))
    }

    @Test
    fun `an integer time with no decimal point parses`() {
        assertEquals(7L, parse("64 bytes from h: icmp_seq=1 ttl=54 time=7 ms"))
    }

    // --- the shapes it gets wrong, recorded ----------------------------------

    /**
     * busybox `ping`, which is what some Android builds ship, prints `time=`
     * with no space before `ms`. `substringBefore(" ms")` then finds no match
     * and returns the whole remainder, "1.23ms", which is not a number.
     *
     * The result is -1: a verdict of failure, reached from a line that said
     * the host answered in 1.23 ms. This is the defect the split makes
     * visible; fixing it means changing the parse, and that is its own change.
     */
    @Test
    fun `a busybox reply with no space before ms is read as a failure`() {
        assertEquals(-1L, parse("64 bytes from h: seq=1 ttl=54 time=1.23ms"))
    }

    /**
     * And it is a verdict, not a shrug: the line decided the outcome, so a
     * well-formed reply further down is never reached.
     */
    @Test
    fun `an unparseable line hides a good reply after it`() {
        assertEquals(
            -1L,
            parse(
                "64 bytes from h: seq=1 ttl=54 time=1.23ms",
                "64 bytes from h: icmp_seq=2 ttl=54 time=5.0 ms",
            ),
        )
    }

    /**
     * The difference between the two failure answers, which the caller relies
     * on: null means no line claimed a time and the caller reaps the process;
     * -1 means a line claimed one and could not be read.
     */
    @Test
    fun `no timed line and an unreadable one are different answers`() {
        assertNull(parse("--- h ping statistics ---"))
        assertEquals(-1L, parse("time=whenever"))
    }
}
