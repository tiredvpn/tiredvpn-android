package com.tiredvpn.android.vpn

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The fold itself, against two real preference stores.
 *
 * [StoreReconciliationTest] covers the decision; this covers the code that acts
 * on it — what is written, what is read back, and what is destroyed. An
 * ordinary `SharedPreferences` stands in for the encrypted one because
 * `EncryptedSharedPreferences` wants a Keystore that a unit test does not have,
 * and nothing here is about the cipher: the defect was that a stale plaintext
 * copy replaced the live list and took the active-server key with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerStoreMigrationTest {

    private lateinit var context: Context
    private lateinit var encrypted: SharedPreferences
    private lateinit var plain: SharedPreferences

    private val keyServers = "servers"
    private val keyActive = "active_server_id"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        encrypted = context.getSharedPreferences("test_enc", Context.MODE_PRIVATE)
        plain = context.getSharedPreferences("test_plain", Context.MODE_PRIVATE)
        encrypted.edit().clear().commit()
        plain.edit().clear().commit()
    }

    private fun record(id: String, name: String = id) =
        """{"id":"$id","name":"$name","secret":"s-$id"}"""

    private fun array(vararg records: String) =
        records.joinToString(prefix = "[", separator = ",", postfix = "]")

    private fun fold() = ServerRepository.migratePlaintextToEncryptedLocked(encrypted, plain)

    private fun storedIds(): List<String> {
        val verdict = ServerStoreIntegrity.classify(encrypted.getString(keyServers, null))
        return when (verdict) {
            is ServerStoreIntegrity.Verdict.Intact -> verdict.elements.mapNotNull { StoreReconciliation.idOf(it) }
            else -> emptyList()
        }
    }

    /**
     * The upgrade scenario. Anyone whose Keystore failed once on an older build
     * has a plaintext copy sitting there; the old migration never looked at it,
     * because the encrypted store already had the key. The new one ran on every
     * read and copied it straight over the live list.
     */
    @Test
    fun `a stale plaintext copy does not replace the live list`() {
        encrypted.edit()
            .putString(keyServers, array(record("ams"), record("usa"), record("dxb")))
            .putString(keyActive, "usa")
            .commit()
        plain.edit()
            .putString(keyServers, array(record("ams", name = "from a year ago")))
            .commit()

        fold()

        assertEquals(listOf("ams", "usa", "dxb"), storedIds())
        assertEquals("the active server must stay chosen", "usa", encrypted.getString(keyActive, null))
        assertTrue(
            "the live record wins over the stale one",
            encrypted.getString(keyServers, null)!!.contains(""""name":"ams"""")
        )
    }

    @Test
    fun `an empty plaintext copy does not empty the live list`() {
        encrypted.edit().putString(keyServers, array(record("ams"))).putString(keyActive, "ams").commit()
        plain.edit().putString(keyServers, "[]").commit()

        fold()

        assertEquals(listOf("ams"), storedIds())
        assertEquals("ams", encrypted.getString(keyActive, null))
    }

    /**
     * `putString(key, null)` removes the key. That is how the active server
     * disappeared on the way past, and why nothing writes null here.
     */
    @Test
    fun `the active server key is never removed by the fold`() {
        encrypted.edit().putString(keyServers, array(record("ams"))).putString(keyActive, "ams").commit()
        plain.edit().putString(keyServers, array(record("ams"))).commit() // no active id of its own

        fold()

        assertTrue(encrypted.contains(keyActive))
        assertEquals("ams", encrypted.getString(keyActive, null))
    }

    @Test
    fun `what was written while degraded is folded in, not discarded`() {
        encrypted.edit().putString(keyServers, array(record("ams"))).commit()
        plain.edit()
            .putString(keyServers, array(record("imported")))
            .putString(keyActive, "imported")
            .commit()

        fold()

        assertEquals(listOf("ams", "imported"), storedIds())
        assertEquals(
            "with nothing chosen on the encrypted side, the degraded choice stands",
            "imported",
            encrypted.getString(keyActive, null)
        )
        assertTrue("a folded source is cleared, or this runs on every read", plain.all.isEmpty())
    }

    @Test
    fun `a damaged plaintext copy is neither folded nor destroyed`() {
        encrypted.edit().putString(keyServers, array(record("ams"))).commit()
        plain.edit().putString(keyServers, """[{"id":"half"""").commit()

        fold()

        assertEquals("the live list is untouched", listOf("ams"), storedIds())
        assertEquals(
            "the damaged copy is the only copy of whatever is in it",
            """[{"id":"half"""",
            plain.getString(keyServers, null)
        )
    }

    @Test
    fun `a damaged encrypted payload is left alone rather than parsed away`() {
        encrypted.edit().putString(keyServers, """[{"id":"half"""").commit()
        plain.edit().putString(keyServers, array(record("ams"))).commit()

        fold()

        assertEquals("""[{"id":"half"""", encrypted.getString(keyServers, null))
        assertEquals(array(record("ams")), plain.getString(keyServers, null))
    }

    @Test
    fun `no plaintext list means nothing is written at all`() {
        encrypted.edit().putString(keyServers, array(record("ams"))).putString(keyActive, "ams").commit()
        plain.edit().putString("some_other_key", "x").commit()

        fold()

        assertEquals(listOf("ams"), storedIds())
        assertEquals("an unrelated key is not ours to clear", "x", plain.getString("some_other_key", null))
    }

    @Test
    fun `a fold that lands is confirmed by reading it back before the source dies`() {
        encrypted.edit().putString(keyServers, array(record("a"))).commit()
        plain.edit().putString(keyServers, array(record("b"))).commit()

        fold()

        assertEquals(listOf("a", "b"), storedIds())
        assertTrue(plain.all.isEmpty())
        assertNull(plain.getString(keyServers, null))
    }
}
