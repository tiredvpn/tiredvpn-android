package com.tiredvpn.android.vpn

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // The plaintext side is the repository's real fallback store, so the
        // tests below can either seed it by hand or drive the repository's own
        // writers and have the result land in the same place.
        encrypted = context.getSharedPreferences("test_enc", Context.MODE_PRIVATE)
        plain = context.getSharedPreferences("tiredvpn_servers", Context.MODE_PRIVATE)
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

    /**
     * End to end through the repository's own writers: the markers are set by
     * the same calls the app makes, not by the test.
     *
     * Robolectric has no Keystore, so `encryptedPrefsOrNull` fails and the
     * repository is genuinely in its degraded mode here — which is the only
     * state in which these marks are supposed to be written at all.
     */
    @Test
    fun `a secret rotated during a degraded spell survives the fold`() {
        val ams = VpnConfig(name = "AMS", serverAddress = "ams.example", serverPort = 995, secret = "old-key")

        // What the encrypted store held before the Keystore went down.
        encrypted.edit()
            .putString(keyServers, """[${ams.toJson()}]""")
            .putString(keyActive, ams.id)
            .commit()

        // What the user did while it was down: rotated the secret.
        ServerRepository.saveServer(context, ams.copy(secret = "rotated-key"))
        assertTrue(
            "these tests need the repository to be in its degraded mode, and it is not - " +
                "the marks under test are only written there",
            ServerRepository.isStorageDegraded
        )

        fold()

        val stored = encrypted.getString(keyServers, null)!!
        assertTrue("the rotation must survive", stored.contains("rotated-key"))
        assertFalse("the superseded secret must not", stored.contains("old-key"))
        assertEquals("and it is one server, not two", listOf(ams.id), storedIds())
    }

    @Test
    fun `a server deleted during a degraded spell is not resurrected`() {
        val ams = VpnConfig(name = "AMS", serverAddress = "ams.example", serverPort = 995, secret = "k1")
        val dxb = VpnConfig(name = "DXB", serverAddress = "dxb.example", serverPort = 995, secret = "k2")

        encrypted.edit()
            .putString(keyServers, """[${ams.toJson()},${dxb.toJson()}]""")
            .putString(keyActive, ams.id)
            .commit()

        ServerRepository.saveServer(context, ams)
        ServerRepository.saveServer(context, dxb)
        ServerRepository.deleteServer(context, dxb.id)

        fold()

        assertEquals(listOf(ams.id), storedIds())
    }

    @Test
    fun `an active server chosen during a degraded spell survives the fold`() {
        val ams = VpnConfig(name = "AMS", serverAddress = "ams.example", serverPort = 995, secret = "k1")
        val dxb = VpnConfig(name = "DXB", serverAddress = "dxb.example", serverPort = 995, secret = "k2")

        encrypted.edit()
            .putString(keyServers, """[${ams.toJson()},${dxb.toJson()}]""")
            .putString(keyActive, ams.id)
            .commit()

        ServerRepository.saveServer(context, ams)
        ServerRepository.saveServer(context, dxb)
        ServerRepository.setActiveServerId(context, dxb.id)

        fold()

        assertEquals("the choice made while degraded is the newer one", dxb.id, encrypted.getString(keyActive, null))
    }

    /**
     * A latency measurement is not a reason to beat the encrypted store: the
     * plaintext list it is written into may be a stale snapshot, and marking it
     * would hand that snapshot the privilege meant for real edits.
     */
    @Test
    fun `a latency ping while degraded does not mark the record as newer`() {
        val ams = VpnConfig(name = "AMS", serverAddress = "ams.example", serverPort = 995, secret = "live-key")

        encrypted.edit().putString(keyServers, """[${ams.toJson()}]""").commit()
        // A stale copy sitting in plaintext from an earlier failure.
        plain.edit().putString(keyServers, """[${ams.copy(secret = "stale-key").toJson()}]""").commit()

        ServerRepository.updateLatency(context, ams.id, 42L)

        fold()

        val stored = encrypted.getString(keyServers, null)!!
        assertTrue("the live secret must win", stored.contains("live-key"))
        assertFalse("a ping must not promote a stale copy", stored.contains("stale-key"))
    }

    /**
     * `getStringSet` throws `ClassCastException` when the key holds something
     * that is not a set — a half-written file, an older layout. Thrown out of
     * the fold it would escape into a plain read; swallowed as "no marks" it
     * would silently demote every degraded-mode change to an old copy. Neither:
     * an unreadable journal is damage, and damage means touch nothing.
     */
    @Test
    fun `an unreadable journal stops the fold and destroys nothing`() {
        encrypted.edit().putString(keyServers, array(record("ams", name = "live"))).commit()
        plain.edit()
            .putString(keyServers, array(record("ams", name = "degraded")))
            .putString("degraded_dirty_ids", "not a set at all")
            .commit()

        fold()

        assertEquals("the encrypted list must be untouched", listOf("ams"), storedIds())
        assertTrue(
            "and it must still be the record it held",
            encrypted.getString(keyServers, null)!!.contains("live")
        )
        assertEquals(
            "the plaintext copy is the only copy of what it holds",
            array(record("ams", name = "degraded")),
            plain.getString(keyServers, null)
        )
    }

    @Test
    fun `a journal flag of the wrong type is damage too`() {
        encrypted.edit().putString(keyServers, array(record("ams"))).commit()
        plain.edit()
            .putString(keyServers, array(record("ams")))
            .putString("degraded_active_id_chosen", "yes")
            .commit()

        fold()

        assertEquals(array(record("ams")), plain.getString(keyServers, null))
    }

    @Test
    fun `a contradictory journal stops the fold and destroys nothing`() {
        encrypted.edit().putString(keyServers, array(record("ams"), record("dxb"))).commit()
        plain.edit()
            .putString(keyServers, array(record("ams")))
            .putStringSet("degraded_dirty_ids", mutableSetOf("ams"))
            .putStringSet("degraded_deleted_ids", mutableSetOf("ams"))
            .commit()

        fold()

        assertEquals("both records stay until somebody can say what happened", listOf("ams", "dxb"), storedIds())
        assertTrue("and the source is kept", plain.contains(keyServers))
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
