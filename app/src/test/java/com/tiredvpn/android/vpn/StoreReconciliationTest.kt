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
        dirtyIds: Set<String> = emptySet(),
        deletedIds: Set<String> = emptySet(),
        activeIdChosenWhileDegraded: Boolean = false,
    ) = StoreReconciliation.plan(
        encrypted, encryptedActiveId, plain, plainActiveId, plainHasList,
        dirtyIds, deletedIds, activeIdChosenWhileDegraded,
    )

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

    // --- what was changed while the Keystore was down -----------------------

    /**
     * The other side of the rule above, and the reason a marker exists at all:
     * a record written during the degraded spell is newer, however identical
     * the two copies look. Rotating a server's secret is the case that costs
     * the user a working tunnel if the old one wins.
     */
    @Test
    fun `a secret changed while degraded beats the encrypted copy`() {
        val plan = fold(
            encrypted = array(record("ams", secret = "old-key")),
            plain = array(record("ams", secret = "rotated-key")),
            dirtyIds = setOf("ams"),
        ) as StoreReconciliation.Plan.Fold

        assertTrue("the rotated secret must survive", plan.payload.contains("rotated-key"))
        assertFalse("the superseded one must not", plan.payload.contains("old-key"))
        assertEquals("and it is still one server, not two", listOf("ams"), plan.expectedIds)
    }

    @Test
    fun `an unmarked copy of the same id still loses`() {
        val plan = fold(
            encrypted = array(record("ams", secret = "live-key")),
            plain = array(record("ams", secret = "year-old-key")),
        ) as StoreReconciliation.Plan.Fold

        assertTrue(plan.payload.contains("live-key"))
        assertFalse(plan.payload.contains("year-old-key"))
    }

    @Test
    fun `a server deleted while degraded does not come back`() {
        val plan = fold(
            encrypted = array(record("ams"), record("dxb")),
            plain = array(record("ams")),
            deletedIds = setOf("dxb"),
        ) as StoreReconciliation.Plan.Fold

        assertEquals(listOf("ams"), plan.expectedIds)
        assertFalse(plan.payload.contains("dxb"))
    }

    @Test
    fun `a delete undone by a later save is not a delete`() {
        // The repository keeps the two sets disjoint; this is the shape the
        // planner must handle when it does.
        val plan = fold(
            encrypted = array(record("ams", secret = "old")),
            plain = array(record("ams", secret = "new")),
            dirtyIds = setOf("ams"),
            deletedIds = emptySet(),
        ) as StoreReconciliation.Plan.Fold
        assertEquals(listOf("ams"), plan.expectedIds)
        assertTrue(plan.payload.contains("new"))
    }

    @Test
    fun `a marked id absent from plaintext leaves the encrypted record alone`() {
        val plan = fold(
            encrypted = array(record("ams", secret = "live")),
            plain = "[]",
            dirtyIds = setOf("ams"),
        ) as StoreReconciliation.Plan.Fold

        assertEquals(listOf("ams"), plan.expectedIds)
        assertTrue(plan.payload.contains("live"))
    }

    @Test
    fun `the order of the encrypted list is kept when a record is replaced`() {
        val plan = fold(
            encrypted = array(record("a"), record("b", secret = "old"), record("c")),
            plain = array(record("b", secret = "new")),
            dirtyIds = setOf("b"),
        ) as StoreReconciliation.Plan.Fold

        assertEquals(listOf("a", "b", "c"), plan.expectedIds)
        assertTrue(plan.payload.contains("new"))
    }

    // --- the active server id -----------------------------------------------

    @Test
    fun `an active server chosen while degraded wins over the encrypted choice`() {
        val plan = fold(
            encrypted = array(record("a"), record("b")),
            plain = array(record("b")),
            encryptedActiveId = "a",
            plainActiveId = "b",
            activeIdChosenWhileDegraded = true,
        ) as StoreReconciliation.Plan.Fold

        assertEquals("b", plan.activeId)
    }

    @Test
    fun `a choice made while degraded is still checked against the merged list`() {
        val plan = fold(
            encrypted = array(record("a")),
            plain = "[]",
            encryptedActiveId = "a",
            plainActiveId = "gone",
            activeIdChosenWhileDegraded = true,
        ) as StoreReconciliation.Plan.Fold

        assertNull("an id naming nothing must not be written", plan.activeId)
    }


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

    /**
     * The two sets are kept disjoint by the repository, so an overlap is not a
     * tie to break — it is the journal not describing what happened. Either
     * resolution destroys something: honouring the delete loses a record the
     * user edited, honouring the edit brings back one they removed.
     */
    @Test
    fun `an id marked both changed and deleted is a refusal, not a tie-break`() {
        val plan = fold(
            encrypted = array(record("ams"), record("dxb")),
            plain = array(record("ams")),
            dirtyIds = setOf("ams"),
            deletedIds = setOf("ams"),
        )
        assertTrue(plan is StoreReconciliation.Plan.Refuse)
        assertTrue(
            "the reason has to name the contradiction",
            (plan as StoreReconciliation.Plan.Refuse).reason.contains("contradicts itself")
        )
    }

    @Test
    fun `sets that only touch different ids are not a contradiction`() {
        val plan = fold(
            encrypted = array(record("ams"), record("dxb")),
            plain = array(record("ams", secret = "new")),
            dirtyIds = setOf("ams"),
            deletedIds = setOf("dxb"),
        ) as StoreReconciliation.Plan.Fold

        assertEquals(listOf("ams"), plan.expectedIds)
        assertTrue(plan.payload.contains("new"))
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
