package com.tiredvpn.android.vpn

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * That a change and the note saying it happened reach the disk together.
 *
 * The degraded-mode journal is evidence about the payload next to it: an id in
 * `degraded_dirty_ids` is what makes the fold prefer the plaintext record over
 * the encrypted one. Written as a second transaction, the evidence can be lost
 * while the payload survives — the process dies between the two enqueued
 * writes, and the next launch sees a rotated secret in plaintext with nothing
 * marking it as new. The fold then reads it as an old copy and keeps the
 * encrypted one, which is the loss the journal exists to prevent, just through
 * a narrower window.
 *
 * There is no way to kill a process in a unit test, so the property is checked
 * where it is decided: a state with the payload and without its mark must never
 * be handed to the store at all. Every transaction is recorded and inspected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoreTransactionTest {

    private val servers = "servers"
    private val dirty = "degraded_dirty_ids"
    private val deleted = "degraded_deleted_ids"
    private val activeChosen = "degraded_active_id_chosen"
    private val activeId = "active_server_id"

    /** The keys of one committed transaction, in the order they were written. */
    private val transactions = mutableListOf<Set<String>>()

    private lateinit var context: Context

    /** A store that remembers what each transaction touched. */
    private inner class RecordingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {

        override fun edit(): SharedPreferences.Editor = RecordingEditor(delegate.edit())

        private inner class RecordingEditor(
            private val inner: SharedPreferences.Editor,
        ) : SharedPreferences.Editor {

            private val touched = mutableSetOf<String>()

            override fun putString(key: String, value: String?) = apply { touched += key; inner.putString(key, value) }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { touched += key; inner.putStringSet(key, values) }
            override fun putInt(key: String, value: Int) = apply { touched += key; inner.putInt(key, value) }
            override fun putLong(key: String, value: Long) = apply { touched += key; inner.putLong(key, value) }
            override fun putFloat(key: String, value: Float) = apply { touched += key; inner.putFloat(key, value) }
            override fun putBoolean(key: String, value: Boolean) = apply { touched += key; inner.putBoolean(key, value) }
            override fun remove(key: String) = apply { touched += key; inner.remove(key) }
            override fun clear() = apply { touched += "*clear*"; inner.clear() }

            override fun commit(): Boolean {
                val ok = inner.commit()
                if (touched.isNotEmpty()) transactions += touched.toSet()
                return ok
            }

            override fun apply() {
                inner.apply()
                if (touched.isNotEmpty()) transactions += touched.toSet()
            }
        }
    }

    private inner class RecordingContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            RecordingPreferences(baseContext.getSharedPreferences(name, mode))
    }

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("tiredvpn_servers", Context.MODE_PRIVATE).edit().clear().commit()
        context = RecordingContext(app)
        // Put the repository in degraded mode and let it settle before anything
        // is recorded: the marks under test are only written there.
        ServerRepository.getServers(context)
        assertTrue(
            "this test needs the repository to be using its plaintext fallback, and it is not",
            ServerRepository.isStorageDegraded
        )
        transactions.clear()
    }

    private fun server(name: String, secret: String = "k") =
        VpnConfig(name = name, serverAddress = "$name.example", serverPort = 995, secret = secret)

    /** Transactions that wrote the payload. */
    private fun payloadWrites() = transactions.filter { servers in it }

    // --- positive control (rule 2) ------------------------------------------

    @Test
    fun `the recorder sees the writes it is asked about`() {
        ServerRepository.saveServer(context, server("AMS"))
        assertTrue("no transaction recorded at all - the wrapper is not in the path", transactions.isNotEmpty())
        assertEquals("exactly one write of the list", 1, payloadWrites().size)
        assertTrue("and it must mention the journal, or the assertions below prove nothing", dirty in payloadWrites().single())
    }

    // --- the rule -----------------------------------------------------------

    @Test
    fun `a save writes the list and its mark in one transaction`() {
        ServerRepository.saveServer(context, server("AMS"))

        for (write in payloadWrites()) {
            assertTrue("a payload write with no mark against it: $write", dirty in write)
        }
    }

    @Test
    fun `a delete writes the list, the successor and its mark in one transaction`() {
        val ams = server("AMS")
        val dxb = server("DXB")
        ServerRepository.saveServer(context, ams)
        ServerRepository.saveServer(context, dxb)
        transactions.clear()

        ServerRepository.deleteServer(context, ams.id)

        assertEquals("a delete is one write, not three", 1, payloadWrites().size)
        val write = payloadWrites().single()
        assertTrue("the delete must be journalled with the list: $write", deleted in write)
        assertTrue("and the successor chosen in the same breath: $write", activeId in write)
    }

    @Test
    fun `choosing the active server writes the choice and its mark together`() {
        val ams = server("AMS")
        val dxb = server("DXB")
        ServerRepository.saveServer(context, ams)
        ServerRepository.saveServer(context, dxb)
        transactions.clear()

        ServerRepository.setActiveServerId(context, dxb.id)

        val writes = transactions.filter { activeId in it }
        assertEquals(1, writes.size)
        assertTrue("the choice must carry the note that it was made while degraded", activeChosen in writes.single())
    }

    /**
     * The first server becomes the active one. That is part of the same change
     * and must not be a second write either.
     */
    @Test
    fun `adopting the first server as active is part of the same transaction`() {
        ServerRepository.saveServer(context, server("AMS"))

        assertEquals(1, payloadWrites().size)
        val write = payloadWrites().single()
        assertTrue("the adoption must ride along with the list: $write", activeId in write)
        assertTrue(dirty in write)
    }

    // --- a refused write is not a saved one --------------------------------

    /**
     * A store that takes every write and commits none of them.
     *
     * Every builder method returns `this` and not the delegate's editor. That
     * is not a detail: `by` delegation forwards `putString` to the inner editor,
     * which returns *itself*, so a chain like `edit().putString(…)` hands back
     * the inner editor and the overridden `commit()` is never reached. The first
     * version of this double did exactly that and reported the production code
     * as broken.
     */
    private inner class RefusingContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = baseContext.getSharedPreferences(name, mode)
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor = RefusingEditor(real.edit())
            }
        }
    }

    private inner class RefusingEditor(
        private val inner: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { inner.putString(key, value) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { inner.putStringSet(key, values) }
        override fun putInt(key: String, value: Int) = apply { inner.putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { inner.putLong(key, value) }
        override fun putFloat(key: String, value: Float) = apply { inner.putFloat(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { inner.putBoolean(key, value) }
        override fun remove(key: String) = apply { inner.remove(key) }
        override fun clear() = apply { inner.clear() }
        override fun commit(): Boolean = false
        override fun apply() = Unit
    }

    /**
     * The caller is the only one that can tell the user. These used to return
     * Unit and write a line to a log file nobody has open, so a refused save
     * looked exactly like a successful one on screen.
     */
    @Test
    fun `a refused write is reported to the caller`() {
        val refusing = RefusingContext(RuntimeEnvironment.getApplication())
        val ams = server("AMS")

        assertFalse("saveServer must not claim success", ServerRepository.saveServer(refusing, ams))
        assertFalse("deleteServer must not claim success", ServerRepository.deleteServer(refusing, ams.id))
        assertFalse("setActiveServerId must not claim success", ServerRepository.setActiveServerId(refusing, ams.id))
    }

    @Test
    fun `a write that lands is reported as landed`() {
        val ams = server("AMS")
        assertTrue(ServerRepository.saveServer(context, ams))
        assertTrue(ServerRepository.setActiveServerId(context, ams.id))
        assertTrue(ServerRepository.deleteServer(context, ams.id))
    }

    /**
     * A latency measurement is deliberately unmarked — see
     * ServerStoreMigrationTest. It must still be one transaction, and it must
     * not invent a mark.
     */
    @Test
    fun `a latency update is one unmarked transaction`() {
        val ams = server("AMS")
        ServerRepository.saveServer(context, ams)
        transactions.clear()

        ServerRepository.updateLatency(context, ams.id, 42L)

        assertEquals(1, payloadWrites().size)
        assertTrue(
            "a ping must not claim the record is newer than the encrypted one",
            dirty !in payloadWrites().single()
        )
    }
}
