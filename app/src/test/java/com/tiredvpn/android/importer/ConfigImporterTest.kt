package com.tiredvpn.android.importer

import android.content.Context
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.SplitTunnelSettings
import com.tiredvpn.android.vpn.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Deduplication and writing.
 *
 * Everything here asserts on what came back out of [ServerRepository], never on
 * the plan alone: a plan that says "4" and a store that holds 1 is exactly the
 * failure this file exists to catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigImporterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun link(host: String, port: Int = 995, secret: String = "k", extra: String = "") =
        "tired://$host:$port?secret=$secret$extra"

    private fun import(payload: String): ConfigImporter.Result =
        ConfigImporter.importDirect(context, payload)

    private fun stored(): List<VpnConfig> = ServerRepository.getServers(context)

    // --- the bulk case ---

    @Test
    fun `importing four links stores four servers`() {
        // This is the whole point of the change: setting up the pool used to mean
        // typing one link four times.
        val payload = listOf("a.example", "b.example", "c.example", "d.example")
            .joinToString("\n") { link(it) }

        val result = import(payload)

        assertEquals(4, result.added)
        assertEquals(0, result.updated)
        assertEquals(4, stored().size)
        assertEquals(
            listOf("a.example", "b.example", "c.example", "d.example"),
            stored().map { it.serverAddress },
        )
    }

    @Test
    fun `each of the four keeps its own secret`() {
        // Core 1.8.0 gives every pool node its own key. Storing four servers that
        // all carry the first node's secret would look like success and fail to
        // connect on three of them.
        val payload = (1..4).joinToString("\n") { link("n$it.example", secret = "key-$it") }

        import(payload)

        assertEquals(
            listOf("key-1", "key-2", "key-3", "key-4"),
            stored().sortedBy { it.serverAddress }.map { it.secret },
        )
    }

    @Test
    fun `a JSON array of four stores four servers`() {
        val payload = (1..4).joinToString(",", "[", "]") {
            """{"server":"n$it.example","port":995,"secret":"key-$it"}"""
        }

        assertEquals(4, import(payload).added)
        assertEquals(4, stored().size)
    }

    // --- deduplication ---

    @Test
    fun `re-importing the same links updates instead of duplicating`() {
        val payload = (1..4).joinToString("\n") { link("n$it.example") }
        import(payload)

        val second = import(payload)

        assertEquals(0, second.added)
        assertEquals(4, second.updated)
        assertEquals(4, stored().size)
    }

    @Test
    fun `a rotated secret on a known endpoint updates that server`() {
        import(link("n1.example", secret = "old-key"))

        val result = import(link("n1.example", secret = "new-key"))

        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals(listOf("new-key"), stored().map { it.secret })
    }

    @Test
    fun `the same endpoint on a different port is a different server`() {
        import(link("n1.example", port = 995))

        assertEquals(1, import(link("n1.example", port = 996)).added)
        assertEquals(2, stored().size)
    }

    @Test
    fun `the same endpoint twice in one payload is stored once and reported`() {
        val payload = "${link("n1.example")}\n${link("n1.example", secret = "other")}"

        val result = import(payload)

        assertEquals(1, result.added)
        assertEquals(1, result.skipped)
        assertEquals(1, stored().size)
    }

    @Test
    fun `the skip reason says why, not just that`() {
        val payload = "${link("n1.example")}\n${link("n1.example")}\ntired://broken:995"
        val plan = ConfigImporter.plan(context, ConfigCodec.parse(payload))

        val reasons = plan.skipped.map { it.reason }
        assertTrue(reasons.contains(ConfigImporter.REASON_DUPLICATE))
        assertTrue(reasons.contains(ConfigCodec.REASON_MALFORMED_LINK))
    }

    @Test
    fun `a backup round-trips by id even after the server moved address`() {
        import(link("old.example"))
        val moved = stored().single().copy(serverAddress = "new.example")

        val result = import("[${moved.toJson()}]")

        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals(listOf("new.example"), stored().map { it.serverAddress })
    }

    // --- what an update keeps and what it replaces ---

    @Test
    fun `an update keeps the stored id so split-tunnel rules stay attached`() {
        import(link("n1.example"))
        val originalId = stored().single().id

        import(link("n1.example", secret = "rotated"))

        assertEquals(originalId, stored().single().id)
    }

    @Test
    fun `an update does not overwrite a name the user chose with a name from a link`() {
        // A bare link names the server after its host. Re-importing the pool must
        // not undo four renames.
        import(link("n1.example"))
        ServerRepository.saveServer(context, stored().single().copy(name = "Amsterdam"))

        import(link("n1.example", secret = "rotated"))

        assertEquals("Amsterdam", stored().single().name)
    }

    @Test
    fun `an update does take a name the sender actually chose`() {
        import(link("n1.example"))
        ServerRepository.saveServer(context, stored().single().copy(name = "Amsterdam"))

        import(link("n1.example", extra = "&name=Rotterdam"))

        assertEquals("Rotterdam", stored().single().name)
    }

    @Test
    fun `an update keeps the locally measured latency, which the sender cannot know`() {
        import(link("n1.example"))
        ServerRepository.saveServer(context, stored().single().copy(lastLatencyMs = 42))

        import(link("n1.example", secret = "rotated"))

        assertEquals(42L, stored().single().lastLatencyMs)
    }

    @Test
    fun `an update replaces the options the sender did send`() {
        import(link("n1.example"))

        import(link("n1.example", extra = "&strategy=reality&serverV6=%5B2001:db8::1%5D:995&preferIpv6=true"))

        val saved = stored().single()
        assertEquals("reality", saved.strategy)
        assertEquals("[2001:db8::1]:995", saved.serverAddressV6)
        assertTrue(saved.preferIpv6)
    }

    // --- side effects ---

    @Test
    fun `split tunneling lands on the profile it arrived with`() {
        import(
            """{"server":"n1.example","port":995,"secret":"k",
                "split_tunneling":{"mode":"include","apps":["com.foo"]}}"""
        )

        val saved = stored().single()
        assertEquals("include", SplitTunnelSettings.getMode(context, saved.id))
        assertEquals(setOf("com.foo"), SplitTunnelSettings.getApps(context, saved.id))
    }

    @Test
    fun `a single imported server becomes the active one`() {
        import(link("n1.example"))

        assertEquals("n1.example", ServerRepository.getActiveServer(context)?.serverAddress)
    }

    @Test
    fun `importing a pool does not move the user onto whichever node came last`() {
        import(link("chosen.example"))
        val chosen = ServerRepository.getActiveServer(context)!!.id

        import((1..4).joinToString("\n") { link("n$it.example") })

        assertEquals(chosen, ServerRepository.getActiveServer(context)?.id)
        assertEquals(5, stored().size)
    }

    // --- nothing to do ---

    @Test
    fun `prose imports nothing and reports nothing`() {
        val result = import("this is not a config")

        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a plan with no writable entries is not offered as work`() {
        val plan = ConfigImporter.plan(context, ConfigCodec.parse("nothing here"))

        assertFalse(plan.hasWork)
    }

    @Test
    fun `an entry label never contains the secret`() {
        val plan = ConfigImporter.plan(context, ConfigCodec.parse(link("n1.example", secret = "hunter2")))

        val label = plan.toAdd.single().label
        assertNotNull(label)
        assertFalse(label.contains("hunter2"))
    }
}
