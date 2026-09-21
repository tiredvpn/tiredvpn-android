package com.tiredvpn.android.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.tiredvpn.android.R
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.TiredVpnService
import com.tiredvpn.android.vpn.VpnConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowVpnService

/**
 * The connect button's promise.
 *
 * TiredVpnService.state is a StateFlow: if the screen paints "Connecting" and
 * then connect() returns without starting anything, no further value is ever
 * emitted and the button stays like that until the process dies. So the tests
 * that matter here are the ones where the tap leads nowhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainScreenTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ServerRepository.getServers(context).forEach { ServerRepository.deleteServer(context, it.id) }
        // The welcome screen would take the first launch away from us.
        context.getSharedPreferences("tiredvpn_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("first_launch", false).apply()
    }

    @After
    fun tearDown() {
        ShadowVpnService.reset()
    }

    private fun configureServer() = ServerRepository.saveServer(
        context,
        VpnConfig(name = "AMS", serverAddress = "ams.example", serverPort = 995, secret = "k")
    )

    /** Make isOtherVpnActive() see a VPN on the active network. */
    private fun pretendAnotherVpnIsUp() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork!!)!!
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun launch(intent: Intent? = null) =
        if (intent == null) Robolectric.buildActivity(MainActivity::class.java).setup().get()
        else Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun statusOf(activity: MainActivity) =
        activity.findViewById<TextView>(R.id.statusText).text.toString()

    @Test
    fun `tapping connect without a server does not leave the button connecting`() {
        val activity = launch()

        activity.findViewById<android.view.View>(R.id.connectButton).performClick()

        assertEquals(
            "nothing was started, so nothing may claim to be connecting",
            activity.getString(R.string.disconnected),
            statusOf(activity)
        )
    }

    @Test
    fun `a tap that does start the service paints connecting`() {
        // The positive control for every "stays Disconnected" test here: the
        // same assertion on a tap that really does reach startVpnService().
        configureServer()
        ShadowVpnService.setPrepareResult(null)   // consent already given
        val activity = launch()

        activity.findViewById<android.view.View>(R.id.connectButton).performClick()

        assertEquals(activity.getString(R.string.connecting), statusOf(activity))
        assertEquals(
            TiredVpnService.ACTION_CONNECT,
            shadowOf(activity).peekNextStartedService()?.action
        )
    }

    @Test
    fun `waiting for the VPN consent dialog does not paint connecting`() {
        // prepare() returns an Intent: the system consent screen is about to
        // open and may well be refused. Nothing has started.
        configureServer()
        ShadowVpnService.setPrepareResult(Intent("android.net.vpn.SETTINGS"))
        val activity = launch()

        activity.findViewById<android.view.View>(R.id.connectButton).performClick()

        assertEquals(activity.getString(R.string.disconnected), statusOf(activity))
    }

    @Test
    fun `dismissing the other-VPN warning does not paint connecting`() {
        configureServer()
        ShadowVpnService.setPrepareResult(null)
        pretendAnotherVpnIsUp()
        val activity = launch()

        activity.findViewById<android.view.View>(R.id.connectButton).performClick()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()   // Cancel
        idle()   // the button posts its click; without this nothing happened

        assertEquals(activity.getString(R.string.disconnected), statusOf(activity))
        assertNull("nothing may have been started", shadowOf(activity).peekNextStartedService())
    }

    @Test
    fun `connecting anyway past the other-VPN warning does paint connecting`() {
        configureServer()
        ShadowVpnService.setPrepareResult(null)
        pretendAnotherVpnIsUp()
        val activity = launch()

        activity.findViewById<android.view.View>(R.id.connectButton).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()  // Connect anyway
        idle()

        assertEquals(activity.getString(R.string.connecting), statusOf(activity))
        assertEquals(
            TiredVpnService.ACTION_CONNECT,
            shadowOf(activity).peekNextStartedService()?.action
        )
    }

    @Test
    fun `the install_update extra is consumed once`() {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_INSTALL_UPDATE, true)

        val activity = launch(intent)

        assertFalse(
            "a recreation must not install the update all over again",
            activity.intent.getBooleanExtra(MainActivity.EXTRA_INSTALL_UPDATE, false)
        )
    }

    @Test
    fun `the battery prompt is not repeated on every start`() {
        shadowOf(context.getSystemService(android.os.PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)
        context.getSharedPreferences("tiredvpn_settings", Context.MODE_PRIVATE)
            .edit().remove("battery_opt_prompt_time").apply()

        launch()
        val shadowApp = shadowOf(RuntimeEnvironment.getApplication())
        val firstRun = generateSequence { shadowApp.nextStartedActivity }
            .count { it.action == android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS }
        assertEquals("the first start should ask", 1, firstRun)

        launch()
        val secondRun = generateSequence { shadowApp.nextStartedActivity }
            .count { it.action == android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS }
        assertEquals("the second start must not ask again", 0, secondRun)
    }
}
