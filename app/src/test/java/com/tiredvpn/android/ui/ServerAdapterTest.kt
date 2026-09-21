package com.tiredvpn.android.ui

import android.os.Looper
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import com.tiredvpn.android.R
import com.tiredvpn.android.util.CountryDetector
import com.tiredvpn.android.vpn.VpnConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The row, not the geolocator.
 *
 * A recycled row that keeps a lookup running writes the answer for the server
 * the user has already scrolled past - a flag next to the wrong address, which
 * no test of [CountryDetector] can see. So these tests drive real view holders
 * through real rebinds and then read the text of the real flag view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerAdapterTest {

    private lateinit var parent: FrameLayout
    private lateinit var adapter: ServerAdapter

    /** One gate per address, so answers can arrive in any order we like. */
    private val gates = mutableMapOf<String, CompletableDeferred<CountryDetector.CountryInfo?>>()

    private val amsterdam = VpnConfig(
        name = "AMS", serverAddress = "ams.example", serverPort = 995, secret = "k1"
    )
    private val dubai = VpnConfig(
        name = "DXB", serverAddress = "dxb.example", serverPort = 995, secret = "k2"
    )

    @Before
    fun setUp() {
        val themed = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_TiredVPN)
        parent = FrameLayout(themed)

        CountryDetector.clearCache()
        CountryDetector.lookup = { address -> gateFor(address).await() }

        adapter = ServerAdapter(
            listOf(amsterdam, dubai),
            null,
            CoroutineScope(Dispatchers.Main),
            onServerClick = {},
            onServerLongClick = {}
        )
    }

    @After
    fun tearDown() {
        CountryDetector.resetTestSeams()
    }

    private fun gateFor(address: String) =
        gates.getOrPut(address) { CompletableDeferred() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun flagOf(holder: ServerAdapter.ServerViewHolder): String =
        holder.itemView.findViewById<TextView>(R.id.flagImage).text.toString()

    private fun newHolder() = adapter.onCreateViewHolder(parent, 0)

    @Test
    fun `an answer for a recycled row does not land on its replacement`() {
        val holder = newHolder()

        adapter.onBindViewHolder(holder, 0)   // shows ams.example, lookup pending
        idle()
        adapter.onBindViewHolder(holder, 1)   // recycled into dxb.example
        idle()

        // The lookup started for the first row finishes late.
        gateFor(amsterdam.serverAddress).complete(CountryDetector.getCountryInfo("NL"))
        idle()

        assertEquals(
            "Netherlands belongs to the row that is no longer here",
            ServerAdapter.UNKNOWN_FLAG,
            flagOf(holder)
        )

        // The row's own answer is still accepted.
        gateFor(dubai.serverAddress).complete(CountryDetector.getCountryInfo("AE"))
        idle()
        assertEquals(CountryDetector.countryCodeToFlag("AE"), flagOf(holder))
    }

    @Test
    fun `rebinding cancels the lookup the holder no longer needs`() {
        val holder = newHolder()

        adapter.onBindViewHolder(holder, 0)
        idle()
        val firstJob = holder.flagJob
        assertNotNull("a cold address must start a lookup", firstJob)

        adapter.onBindViewHolder(holder, 1)
        idle()

        assertTrue("the outgoing lookup must be cancelled", firstJob!!.isCancelled)
    }

    @Test
    fun `recycling cancels the lookup`() {
        val holder = newHolder()

        adapter.onBindViewHolder(holder, 0)
        idle()
        val job = holder.flagJob

        adapter.onViewRecycled(holder)

        assertTrue(job!!.isCancelled)
        assertNull(holder.flagJob)
    }

    @Test
    fun `a known address paints its flag without starting a lookup`() {
        val holder = newHolder()

        // First bind: cold, goes out to the network.
        adapter.onBindViewHolder(holder, 0)
        idle()
        gateFor(amsterdam.serverAddress).complete(CountryDetector.getCountryInfo("NL"))
        idle()
        assertEquals(CountryDetector.countryCodeToFlag("NL"), flagOf(holder))

        // Every repaint after a finished ping rebinds the same row. That must
        // not cost a coroutine, let alone an HTTPS request.
        adapter.onBindViewHolder(holder, 0)

        assertNull("a cached address must not start a lookup", holder.flagJob)
        assertEquals(CountryDetector.countryCodeToFlag("NL"), flagOf(holder))
    }
}
