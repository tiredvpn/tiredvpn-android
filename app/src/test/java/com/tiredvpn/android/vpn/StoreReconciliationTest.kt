package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the plaintext fallback store is allowed to do to the encrypted one.
 *
 * The rule under test is one-directional and the reason is that secrets live in
 * exactly one place: a server that exists on either side has to exist after the
 * fold. The previous version was a copy — plaintext payload over encrypted
 * payload, plaintext active id over encrypted active id, source cleared — run on
 * every read, which meant a plaintext copy left behind by a Keystore failure on
 * some older build replaced the real list on the first launch after an update.
 */
class StoreReconciliationTest {

    private fun record(id: String, name: String = id, secret: String = "s-$id") =
        """{"id":"$id","name":"$name","secret":"$secret"}"""

    private fun array(vararg records: String) = records.joinToString(prefix = "[", separator = ",", postfix = "]")

    private fun fold(
        encrypted: String?,
        plain: String?,
        encryptedActiveId: String? = null,
        plainActiveId: String? = null,
        plainHasList: Boolean = true,
    ) = StoreReconciliation.plan(encrypted, encryptedActiveId, plain, plainActiveId, plainHasList)

    // --- positive control: the ids this whole file turns on are readable -----

    @Test
    fun `the id of a record is read from its top level`() {
        assertEquals("a", StoreReconciliation.idOf(record("a")))
        assertEquals(
            "the id must be found wherever it sits among the keys",
            "b",
            StoreReconciliation.idOf("""{"name":"home","id":"b","port":995}""")
        )
        assertEquals(
            "an escaped quote inside an earlier value must not shift the scan",
            "c",
            StoreReconciliation.idOf("""{"name":"say \"hi\", ok","id":"c"}""")
        )
        assertNull(
            "a nested id belongs to something else",
            StoreReconciliation.idOf("""{"name":"x","meta":{"id":"nested"}}""")
        )
        assertNull(StoreReconciliation.idOf("""{"name":"x"}"""))
        assertNull(StoreReconciliation.idOf("not an object"))
    }

    // --- the blocker --------------------------------------------------------

    @Test
    fun `a stale plaintext copy cannot replace a full encrypted list`() {
        val plan = fold(
            encrypted = array(record("a"), record("b"), record("c")),
            plain = array(record("a", name = "stale")),
        )

        val ids = (plan as StoreReconciliation.Plan.Fold).expectedIds
        assertEquals("every server must survive the fold", listOf("a", "b", "c"), ids)
        assertTrue(
            "the encrypted record wins where both sides know the id",
            plan.payload.contains(""""name":"a"""")
        )
        assertFalse("the stale copy must not come back", plan.payload.contains("stale"))
    }

    @Test
    fun `an empty plaintext copy cannot empty the encrypted list`() {
        val plan = fold(encrypted = array(record("a"), record("b")), plain = "[]")
        assertEquals(listOf("a", "b"), (plan as StoreReconciliation.Plan.Fold).expectedIds)
    }

    /**
     * The other half of the same rule: what the user did while the Keystore was
     * unavailable is also data, and an import made during a degraded spell
     * mints a fresh id.
     */
    @Test
    fun `a server imported while degraded survives the fold`() {
        val plan = fold(
            encrypted = array(record("a")),
            plain = array(record("imported")),
        )
        assertEquals(listOf("a", "imported"), (plan as StoreReconciliation.Plan.Fold).expectedIds)
    }

    @Test
    fun `a record with no readable id is kept rather than dropped`() {
        val plan = fold(encrypted = "[]", plain = array("""{"name":"nameless"}"""))
        val payload = (plan as StoreReconciliation.Plan.Fold).payload
        assertTrue(payload.contains("nameless"))
        assertEquals("it has no id to promise", emptyList<String>(), plan.expectedIds)
    }

    // --- the active server id -----------------------------------------------

    @Test
    fun `the active id is never cleared`() {
        val plan = fold(
            encrypted = array(record("a")),
            plain = array(record("a")),
            encryptedActiveId = "a",
            plainActiveId = null,
        )
        assertNull(
            "null here used to be written straight into the key, which removes it",
            (plan as StoreReconciliation.Plan.Fold).activeId
        )
    }

    @Test
    fun `the plaintext active id does not displace one already chosen`() {
        val plan = fold(
            encrypted = array(record("a"), record("b")),
            plain = array(record("b")),
            encryptedActiveId = "a",
            plainActiveId = "b",
        )
        assertNull((plan as StoreReconciliation.Plan.Fold).activeId)
    }

    @Test
    fun `the plaintext active id is adopted when nothing is chosen yet`() {
        val plan = fold(
            encrypted = "[]",
            plain = array(record("b")),
            encryptedActiveId = null,
            plainActiveId = "b",
        )
        assertEquals("b", (plan as StoreReconciliation.Plan.Fold).activeId)
    }

    @Test
    fun `an active id naming nothing in the merged list is not adopted`() {
        val plan = fold(
            encrypted = array(record("a")),
            plain = "[]",
            plainActiveId = "gone",
        )
        assertNull((plan as StoreReconciliation.Plan.Fold).activeId)
    }

    // --- refusals -----------------------------------------------------------

    @Test
    fun `nothing is folded and nothing is cleared when either side is damaged`() {
        assertTrue(
            "a damaged plaintext copy is the last copy of whatever is in it",
            fold(encrypted = array(record("a")), plain = """[{"id":"x"}""") is StoreReconciliation.Plan.Refuse
        )
        assertTrue(
            "merging a damaged encrypted payload means dropping it",
            fold(encrypted = """not json""", plain = array(record("a"))) is StoreReconciliation.Plan.Refuse
        )
    }

    @Test
    fun `no plaintext list at all is not a fold`() {
        assertTrue(
            fold(encrypted = array(record("a")), plain = null, plainHasList = false)
                is StoreReconciliation.Plan.Nothing
        )
    }

    /**
     * The key is present and empty — a degraded spell that wrote and then
     * deleted everything. That is still a fold, because the source has to be
     * cleared or this runs again on every single read.
     */
    @Test
    fun `an empty but present plaintext list is folded so the source can be cleared`() {
        assertTrue(
            fold(encrypted = array(record("a")), plain = "[]", plainHasList = true)
                is StoreReconciliation.Plan.Fold
        )
    }

    // --- the read-back guard ------------------------------------------------

    @Test
    fun `containsAll is what licenses clearing the source`() {
        val payload = array(record("a"), record("b"))
        assertTrue(StoreReconciliation.containsAll(payload, listOf("a", "b")))
        assertFalse(
            "a missing record must block the clear",
            StoreReconciliation.containsAll(array(record("a")), listOf("a", "b"))
        )
        assertFalse(
            "an unreadable read-back must block the clear",
            StoreReconciliation.containsAll("""[{"id":"a"}""", listOf("a"))
        )
        assertFalse(StoreReconciliation.containsAll(null, listOf("a")))
        assertTrue("nothing promised, nothing to check", StoreReconciliation.containsAll("[]", emptyList()))
    }

    @Test
    fun `the merged payload is a readable array again`() {
        val plan = fold(
            encrypted = array(record("a"), record("b")),
            plain = array(record("c")),
        ) as StoreReconciliation.Plan.Fold

        val verdict = ServerStoreIntegrity.classify(plan.payload)
        assertTrue(verdict is ServerStoreIntegrity.Verdict.Intact)
        assertEquals(3, (verdict as ServerStoreIntegrity.Verdict.Intact).elements.size)
        assertTrue(StoreReconciliation.containsAll(plan.payload, plan.expectedIds))
    }
}
