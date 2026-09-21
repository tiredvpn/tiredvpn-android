package com.tiredvpn.android.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.tiredvpn.android.R
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.TiredVpnService
import com.tiredvpn.android.vpn.VpnConfig
import com.tiredvpn.android.vpn.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowToast

/**
 * The settings dialogs, not the validator.
 *
 * Each of these fields used to take whatever was typed, or drop it without a
 * word, which from the far side of the screen looks exactly like "saved". So
 * the tests fill the real dialog, press the real Save, and read the record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsDialogTest {

    private lateinit var context: Context
    private lateinit var activity: SettingsActivity

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ServerRepository.getServers(context).forEach { ServerRepository.deleteServer(context, it.id) }
        ServerRepository.saveServer(
            context,
            VpnConfig(name = "AMS", serverAddress = "ams.example", serverPort = 995, secret = "k")
        )
        serviceState().value = VpnState.Disconnected
        activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    }

    @After
    fun tearDown() {
        serviceState().value = VpnState.Disconnected
    }

    /**
     * The service publishes its state through a private MutableStateFlow and
     * this package may not change that, so the test reaches for the field.
     * A rename breaks this loudly, which is the right kind of breakage.
     */
    @Suppress("UNCHECKED_CAST")
    private fun serviceState(): MutableStateFlow<VpnState> =
        TiredVpnService::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(null) as MutableStateFlow<VpnState>

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun stored() = ServerRepository.getActiveServer(context)!!

    private fun openDialog(rowId: Int): AlertDialog {
        activity.findViewById<View>(rowId).performClick()
        idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun AlertDialog.fields(): List<EditText> {
        val found = mutableListOf<EditText>()
        fun walk(view: View) {
            if (view is EditText) found += view
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(window!!.decorView)
        return found
    }

    private fun AlertDialog.press(button: Int) {
        getButton(button).performClick()
        idle()
    }

    // --- proxy port ---

    @Test
    fun `a usable proxy port is stored`() {
        val dialog = openDialog(R.id.proxyPortRow)
        dialog.fields().first().setText("9090")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals(9090, stored().proxyPort)
    }

    @Test
    fun `a proxy port out of range is refused, not silently dropped`() {
        val dialog = openDialog(R.id.proxyPortRow)
        dialog.fields().first().setText("70000")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals("the old value must survive", 8080, stored().proxyPort)
        assertEquals(
            activity.getString(
                R.string.invalid_port,
                InputValidation.MIN_USER_PORT,
                InputValidation.MAX_PORT
            ),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun `a privileged proxy port is refused`() {
        val dialog = openDialog(R.id.proxyPortRow)
        dialog.fields().first().setText("80")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals(8080, stored().proxyPort)
    }

    // --- IPv6 endpoint ---

    @Test
    fun `a well-formed IPv6 endpoint is stored`() {
        val dialog = openDialog(R.id.ipv6Row)
        dialog.fields().first().setText("[2001:470:1f0a:8eb::2]:995")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals("[2001:470:1f0a:8eb::2]:995", stored().serverAddressV6)
    }

    @Test
    fun `an endpoint without a port is refused`() {
        val dialog = openDialog(R.id.ipv6Row)
        dialog.fields().first().setText("2001:470:1f0a:8eb::2")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals("", stored().serverAddressV6)
        assertEquals(activity.getString(R.string.invalid_v6_endpoint), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an empty endpoint still means off`() {
        ServerRepository.saveServer(context, stored().copy(serverAddressV6 = "[::1]:995"))
        val dialog = openDialog(R.id.ipv6Row)
        dialog.fields().first().setText("")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals("", stored().serverAddressV6)
    }

    // --- ECH ---

    @Test
    fun `an ECH config that is not base64 is refused`() {
        val dialog = openDialog(R.id.echRow)
        dialog.fields()[0].setText("this is not base64!")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals("", stored().echConfig)
        assertEquals(activity.getString(R.string.invalid_base64), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a base64 ECH config is stored`() {
        val dialog = openDialog(R.id.echRow)
        dialog.fields()[0].setText("AEX+DQBBAAAgACDS")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals("AEX+DQBBAAAgACDS", stored().echConfig)
    }

    @Test
    fun `an ECH public name that is not a host is refused`() {
        val dialog = openDialog(R.id.echRow)
        dialog.fields()[1].setText("not a host name")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals("cloudflare-ech.com", stored().echPublicName)
    }

    // --- cover host ---

    @Test
    fun `a cover host that is not a host is refused`() {
        val dialog = openDialog(R.id.coverHostRow)
        dialog.fields().first().setText("https://example.com/path")
        dialog.press(AlertDialog.BUTTON_POSITIVE)   // "Custom"

        assertEquals("api.googleapis.com", stored().coverHost)
        assertEquals(activity.getString(R.string.invalid_host), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a real cover host is stored`() {
        val dialog = openDialog(R.id.coverHostRow)
        dialog.fields().first().setText("www.google.com")
        dialog.press(AlertDialog.BUTTON_POSITIVE)

        assertEquals("www.google.com", stored().coverHost)
    }

    // --- connection mode ---

    @Test
    fun `changing the mode while connected reconnects only once the tunnel is down`() {
        serviceState().value = VpnState.Connected(strategy = "reality")

        val dialog = openDialog(R.id.connectionModeRow)
        dialog.listView.performItemClick(null, 1, 1L)   // the other mode
        idle()

        assertEquals("proxy", stored().connectionMode)
        assertEquals(
            "the teardown must be asked for first",
            TiredVpnService.ACTION_DISCONNECT,
            shadowOf(activity).peekNextStartedService()?.action
        )
        shadowOf(activity).nextStartedService
        assertNull(
            "a CONNECT sent mid-teardown is swallowed by the service",
            shadowOf(activity).peekNextStartedService()
        )

        // The service finishes tearing down.
        serviceState().value = VpnState.Disconnected
        idle()

        assertEquals(
            TiredVpnService.ACTION_CONNECT,
            shadowOf(activity).peekNextStartedService()?.action
        )
    }

    @Test
    fun `changing the mode while connecting also reconnects`() {
        serviceState().value = VpnState.Connecting

        val dialog = openDialog(R.id.connectionModeRow)
        dialog.listView.performItemClick(null, 1, 1L)
        idle()

        assertEquals(
            TiredVpnService.ACTION_DISCONNECT,
            shadowOf(activity).nextStartedService?.action
        )

        serviceState().value = VpnState.Disconnected
        idle()

        assertEquals(
            "a mode change mid-connect must not leave the old mode running",
            TiredVpnService.ACTION_CONNECT,
            shadowOf(activity).peekNextStartedService()?.action
        )
    }

    @Test
    fun `changing the mode while disconnected starts nothing`() {
        val dialog = openDialog(R.id.connectionModeRow)
        dialog.listView.performItemClick(null, 1, 1L)
        idle()

        assertEquals("proxy", stored().connectionMode)
        assertNull(shadowOf(activity).peekNextStartedService())
    }
}
