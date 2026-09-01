package com.tiredvpn.android.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSocketProtocolTest {

    /** Shapes taken from internal/tun/control.go: EventMessage and ControlResponse. */
    private val keepalive = """{"event":"keepalive","timestamp":1756742000000,"data":""}"""
    private val connectionDead =
        """{"event":"connection_dead","timestamp":1756742000000,"data":"read timeout"}"""
    private val waitingFd =
        """{"status":"waiting_fd","ip":"10.8.0.32","server_ip":"10.8.0.1","dns":"8.8.8.8","mtu":1400}"""
    private val errorResponse = """{"status":"error","error":"TUN handshake failed: EOF"}"""

    @Test
    fun `events are recognised`() {
        assertTrue(ControlSocketProtocol.isEvent(keepalive))
        assertTrue(ControlSocketProtocol.isEvent(connectionDead))
    }

    /**
     * The bug this guards: a single readLine() took whatever arrived first and
     * tested it for status == "waiting_fd". A keepalive landing in front of the
     * response failed the connect on a healthy core.
     */
    @Test
    fun `responses are not mistaken for events`() {
        assertFalse(ControlSocketProtocol.isEvent(waitingFd))
        assertFalse(ControlSocketProtocol.isEvent(errorResponse))
        assertTrue(ControlSocketProtocol.isResponse(waitingFd))
        assertTrue(ControlSocketProtocol.isResponse(errorResponse))
    }

    /** An event must never be consumed as the answer to our command. */
    @Test
    fun `events are not responses`() {
        assertFalse(ControlSocketProtocol.isResponse(keepalive))
        assertFalse(ControlSocketProtocol.isResponse(connectionDead))
    }

    /**
     * Garbage must not be skipped as if it were an event, or the read loop
     * would spin until the socket timeout instead of surfacing the problem.
     */
    @Test
    fun `unparseable lines are not treated as events`() {
        assertFalse(ControlSocketProtocol.isEvent("not json at all"))
        assertFalse(ControlSocketProtocol.isEvent(""))
        assertFalse(ControlSocketProtocol.isEvent("{broken"))
    }

    /**
     * If a line ever carried both fields, status wins: dropping a real response
     * costs a whole connect, skipping one extra event costs nothing.
     */
    @Test
    fun `a line carrying both fields counts as a response`() {
        val both = """{"status":"waiting_fd","event":"connected","ip":"10.8.0.32"}"""
        assertFalse(ControlSocketProtocol.isEvent(both))
        assertTrue(ControlSocketProtocol.isResponse(both))
    }

    /**
     * The reason for scanning top-level keys instead of matching text. The core
     * puts free-form text in an event's "data", and connection_dead already
     * carries error strings there. A substring match on `"status":` would read
     * this event as a response and fail the connect.
     */
    @Test
    fun `status inside an event payload does not make it a response`() {
        val sneaky =
            """{"event":"connection_dead","timestamp":1,"data":"server said \"status\":\"error\""}"""
        assertTrue(ControlSocketProtocol.isEvent(sneaky))
        assertFalse(ControlSocketProtocol.isResponse(sneaky))
    }

    /** A nested object's key is not a top-level key. */
    @Test
    fun `nested keys are not top level keys`() {
        val nested = """{"event":"connected","payload":{"status":"ok"}}"""
        assertTrue(ControlSocketProtocol.isEvent(nested))
        assertFalse(ControlSocketProtocol.isResponse(nested))
    }

    /**
     * Depth tracking specifically, not just "a value is skipped". A comma
     * inside the nested object would otherwise re-arm the key scanner, and the
     * next name would be collected as if it sat at the top level. The simpler
     * nested case above passes even without depth tracking, so it cannot stand
     * in for this one.
     */
    @Test
    fun `a comma inside a nested object does not re-arm the key scanner`() {
        val nested = """{"event":"connected","payload":{"attempts":3,"status":"ok"}}"""
        assertFalse(ControlSocketProtocol.isResponse(nested))
        assertTrue(ControlSocketProtocol.isEvent(nested))

        val nestedArray = """{"event":"connected","tried":["a","b"],"data":""}"""
        assertTrue(ControlSocketProtocol.isEvent(nestedArray))
    }

    /** Whitespace between name, colon and value must not hide the key. */
    @Test
    fun `whitespace around the key is tolerated`() {
        assertTrue(ControlSocketProtocol.isEvent("""{ "event" : "keepalive" , "data" : "" }"""))
        assertTrue(ControlSocketProtocol.isResponse("""{ "status" : "waiting_fd" }"""))
    }

    /** Real payload shapes: routes and dual-stack fields must not confuse the scan. */
    @Test
    fun `a full waiting_fd response is recognised`() {
        val full = """{"status":"waiting_fd","ip":"10.8.0.32","server_ip":"10.8.0.1",""" +
            """"ip6":"fd00::2","server_ip6":"fd00::1","dns":"8.8.8.8","mtu":1400,""" +
            """"routes":"0.0.0.0/1,128.0.0.0/1"}"""
        assertTrue(ControlSocketProtocol.isResponse(full))
        assertFalse(ControlSocketProtocol.isEvent(full))
    }
}
