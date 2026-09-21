package com.tiredvpn.android.ui

import android.content.Context
import android.os.Looper
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.VpnConfig
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The screen's ping wave, not the pinger.
 *
 * A ping takes up to three seconds, and the screen used to write the result
 * back as `server.copy(lastLatencyMs = …)` - a whole record captured before the
 * wait. Anything that changed meanwhile lost: a deleted server came back from
 * the dead, an import or a settings edit was overwritten by the stale copy.
 * So every test here holds a real ping open, changes the store underneath it,
 * and then asks [ServerRepository] what survived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerListPingTest {

    private lateinit var context: Context
    private lateinit var controller: ActivityController<ServerListActivity>
    private lateinit var activity: ServerListActivity

    /**
     * Every ping ever started, in order, each with its own gate. Per-call and
     * not per-server on purpose: the second wave pings the same servers as the
     * first, and a test about a stale result has to answer exactly one of them.
     */
    private val pings = mutableListOf<Pair<String, CompletableDeferred<Long>>>()

    private val ams = VpnConfig(
        name = "AMS", serverAddress = "ams.example", serverPort = 995, secret = "k1"
    )
    private val dxb = VpnConfig(
        name = "DXB", serverAddress = "dxb.example", serverPort = 995, secret = "k2"
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ServerRepository.getServers(context).forEach { ServerRepository.deleteServer(context, it.id) }
        ServerRepository.saveServer(context, ams)
        ServerRepository.saveServer(context, dxb)

        // create()/start() do not refresh the list; onResume does. That gap is
        // where the fake pinger goes in.
        controller = Robolectric.buildActivity(ServerListActivity::class.java).create().start()
        activity = controller.get()
        activity.latencyProbe = { server ->
            val gate = CompletableDeferred<Long>()
            pings += server.id to gate
            gate.await()
        }
    }

    /** The gate of the newest ping started for [id]. */
    private fun gateFor(id: String) = pings.last { it.first == id }.second

    /** The gate of the first ping ever started for [id]. */
    private fun firstGateFor(id: String) = pings.first { it.first == id }.second

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun stored(id: String) = ServerRepository.getServers(context).find { it.id == id }

    @Test
    fun `a server deleted during its ping is not resurrected`() {
        controller.resume()
        idle()

        ServerRepository.deleteServer(context, dxb.id)
        assertNull(stored(dxb.id))

        gateFor(dxb.id).complete(42L)
        idle()

        assertNull("the ping result must not re-create a deleted server", stored(dxb.id))
        assertNotNull("the other server is untouched", stored(ams.id))
    }

    @Test
    fun `an edit made during a ping survives the ping result`() {
        controller.resume()
        idle()

        // Someone else - an import, the settings screen - rewrites the record.
        ServerRepository.saveServer(context, ams.copy(name = "AMS renamed", serverPort = 443))

        gateFor(ams.id).complete(17L)
        idle()

        val after = stored(ams.id)
        assertEquals("the stale copy must not roll the name back", "AMS renamed", after?.name)
        assertEquals(443, after?.serverPort)
        assertEquals("the latency itself must still be written", 17L, after?.lastLatencyMs)
    }

    @Test
    fun `a new wave cancels the one still in flight`() {
        controller.resume()
        idle()
        val firstWave = activity.pingJob
        assertNotNull(firstWave)

        controller.pause().resume()   // back on screen: a second wave starts
        idle()

        assertTrue("the previous wave must not outlive the list it described", firstWave!!.isCancelled)
        assertTrue(activity.pingJob !== firstWave)
    }

    @Test
    fun `a result from a cancelled wave is dropped`() {
        controller.resume()
        idle()

        controller.pause().resume()
        idle()

        // The first wave's socket finally answers, long after its list was
        // dropped. The second wave's ping for the same server is still open.
        firstGateFor(ams.id).complete(99L)
        idle()

        assertEquals(
            "a cancelled wave must not write anything",
            -1L,
            stored(ams.id)?.lastLatencyMs
        )
    }
}
