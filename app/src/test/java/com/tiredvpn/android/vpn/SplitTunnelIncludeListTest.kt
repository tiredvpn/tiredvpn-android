package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelIncludeListTest {

    private val own = "com.tiredvpn.android"
    private val companions = listOf("com.google.android.gms", "com.google.android.gsf")

    @Test
    fun `the app itself never ends up in the include list`() {
        val list = SplitTunnelSettings.includeList(setOf("org.telegram.messenger", own), own, companions)
        assertFalse(own in list)
        assertEquals(setOf("org.telegram.messenger"), list)
    }

    @Test
    fun `only the app itself selected leaves nothing to allow`() {
        assertTrue(SplitTunnelSettings.includeList(setOf(own), own, companions).isEmpty())
    }

    @Test
    fun `google companions are still added for a tunnelled google app`() {
        val list = SplitTunnelSettings.includeList(setOf("com.google.android.apps.meetings"), own, companions)
        assertTrue(list.containsAll(companions))
    }

    @Test
    fun `a selection without the app is passed through unchanged`() {
        val selected = setOf("a.b", "c.d")
        assertEquals(selected, SplitTunnelSettings.includeList(selected, own, companions))
    }
}
