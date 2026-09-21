package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerStoreIntegrityTest {

    @Test
    fun `an empty array has no elements`() {
        assertEquals(emptyList<String>(), JsonArraySplitter.elements("[]"))
        assertEquals(emptyList<String>(), JsonArraySplitter.elements("  [  ]  "))
    }

    @Test
    fun `objects are split at top level only`() {
        val elements = JsonArraySplitter.elements("""[{"id":"a"},{"id":"b"},{"id":"c"}]""")
        assertEquals(listOf("""{"id":"a"}""", """{"id":"b"}""", """{"id":"c"}"""), elements)
    }

    /**
     * A comma inside a nested object or a string must not split an element —
     * that is how a per-element parser silently turns three servers into six
     * fragments.
     */
    @Test
    fun `commas inside nested structures do not split`() {
        val raw = """[{"id":"a","apps":["x","y"],"meta":{"k":1,"j":2}},{"id":"b"}]"""
        val elements = JsonArraySplitter.elements(raw)!!
        assertEquals(2, elements.size)
        assertEquals("""{"id":"a","apps":["x","y"],"meta":{"k":1,"j":2}}""", elements[0])
        assertEquals("""{"id":"b"}""", elements[1])
    }

    @Test
    fun `commas and brackets inside strings do not split`() {
        val raw = """[{"name":"home, [work]"},{"name":"b"}]"""
        val elements = JsonArraySplitter.elements(raw)!!
        assertEquals(2, elements.size)
        assertEquals("""{"name":"home, [work]"}""", elements[0])
    }

    @Test
    fun `an escaped quote does not end a string`() {
        val raw = """[{"name":"say \"hi\", ok"},{"name":"b"}]"""
        val elements = JsonArraySplitter.elements(raw)!!
        assertEquals(2, elements.size)
        assertTrue(elements[0].contains("""\"hi\""""))
    }

    @Test
    fun `an unclosed array is not an array`() {
        assertNull(JsonArraySplitter.elements("""[{"id":"a"},{"id":"b"}"""))
        assertNull(JsonArraySplitter.elements("""[{"id":"a"}]]"""))
        assertNull(JsonArraySplitter.elements("""{"id":"a"}"""))
        assertNull(JsonArraySplitter.elements(""))
    }

    @Test
    fun `an unterminated string is damage`() {
        assertNull(JsonArraySplitter.elements("""[{"name":"unterminated}]"""))
    }

    @Test
    fun `a trailing comma leaves an empty slot and is damage`() {
        assertNull(JsonArraySplitter.elements("""[{"id":"a"},]"""))
        assertNull(JsonArraySplitter.elements("""[{"id":"a"},,{"id":"b"}]"""))
    }

    /**
     * The point of the whole file: "no servers" and "the stored list is
     * damaged" must not produce the same answer. The old loader returned an
     * empty list for both, and the migration then wiped the only copy of the
     * payload it had failed to read.
     */
    @Test
    fun `empty and corrupt are different verdicts`() {
        assertTrue(ServerStoreIntegrity.classify(null) is ServerStoreIntegrity.Verdict.Empty)
        assertTrue(ServerStoreIntegrity.classify("") is ServerStoreIntegrity.Verdict.Empty)
        assertTrue(ServerStoreIntegrity.classify("[]") is ServerStoreIntegrity.Verdict.Empty)

        assertTrue(ServerStoreIntegrity.classify("""[{"id":"a"}""") is ServerStoreIntegrity.Verdict.Corrupt)
        assertTrue(ServerStoreIntegrity.classify("not json at all") is ServerStoreIntegrity.Verdict.Corrupt)
    }

    @Test
    fun `an intact verdict carries every element`() {
        val verdict = ServerStoreIntegrity.classify("""[{"id":"a"},{"id":"b"}]""")
        assertTrue(verdict is ServerStoreIntegrity.Verdict.Intact)
        assertEquals(2, (verdict as ServerStoreIntegrity.Verdict.Intact).elements.size)
    }

    @Test
    fun `a damaged source is never safe to clear`() {
        assertFalse(ServerStoreIntegrity.isSafeToClearSource("""[{"id":"a"}"""))
        assertTrue(ServerStoreIntegrity.isSafeToClearSource("""[{"id":"a"}]"""))
        assertTrue(ServerStoreIntegrity.isSafeToClearSource("[]"))
        assertTrue(ServerStoreIntegrity.isSafeToClearSource(null))
    }
}
