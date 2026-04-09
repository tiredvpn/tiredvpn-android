package com.tiredvpn.android.vpn

import org.junit.Assert.*
import org.junit.Test

class VpnStateTest {

    // --- Singletons ---

    @Test
    fun `Disconnected is a singleton`() {
        assertSame(VpnState.Disconnected, VpnState.Disconnected)
    }

    @Test
    fun `Connecting is a singleton`() {
        assertSame(VpnState.Connecting, VpnState.Connecting)
    }

    // --- Connected defaults ---

    @Test
    fun `Connected has correct default values`() {
        val state = VpnState.Connected(strategy = "auto")

        assertEquals(0L, state.latencyMs)
        assertEquals(1, state.attempts)
        assertEquals("tun", state.mode)
        assertNull(state.proxyAddress)
        assertNull(state.currentPort)
        assertFalse(state.portHoppingEnabled)
    }

    // --- Connected with all fields ---

    @Test
    fun `Connected stores all fields`() {
        val state = VpnState.Connected(
            strategy = "reality",
            latencyMs = 150,
            attempts = 3,
            mode = "proxy",
            proxyAddress = "127.0.0.1:8080",
            currentPort = 48000,
            portHoppingEnabled = true
        )

        assertEquals("reality", state.strategy)
        assertEquals(150L, state.latencyMs)
        assertEquals(3, state.attempts)
        assertEquals("proxy", state.mode)
        assertEquals("127.0.0.1:8080", state.proxyAddress)
        assertEquals(48000, state.currentPort)
        assertTrue(state.portHoppingEnabled)
    }

    // --- Error ---

    @Test
    fun `Error stores message`() {
        val state = VpnState.Error("connection refused")
        assertEquals("connection refused", state.message)
    }

    // --- Sealed class type checking ---

    @Test
    fun `type checking works for all states`() {
        val disconnected: VpnState = VpnState.Disconnected
        val connecting: VpnState = VpnState.Connecting
        val connected: VpnState = VpnState.Connected(strategy = "auto")
        val error: VpnState = VpnState.Error("fail")

        assertTrue(disconnected is VpnState.Disconnected)
        assertTrue(connecting is VpnState.Connecting)
        assertTrue(connected is VpnState.Connected)
        assertTrue(error is VpnState.Error)

        assertFalse(disconnected is VpnState.Connected)
        assertFalse(connecting is VpnState.Error)
    }

    // --- copy ---

    @Test
    fun `Connected copy preserves unchanged fields`() {
        val original = VpnState.Connected(
            strategy = "quic",
            latencyMs = 50,
            attempts = 2,
            mode = "tun",
            proxyAddress = null,
            currentPort = 47000,
            portHoppingEnabled = true
        )

        val copied = original.copy(latencyMs = 100)

        assertEquals("quic", copied.strategy)
        assertEquals(100L, copied.latencyMs)
        assertEquals(2, copied.attempts)
        assertEquals("tun", copied.mode)
        assertNull(copied.proxyAddress)
        assertEquals(47000, copied.currentPort)
        assertTrue(copied.portHoppingEnabled)
    }
}
