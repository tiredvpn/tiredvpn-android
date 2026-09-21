package com.tiredvpn.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.tiredvpn.android.R
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * The log leaving the screen.
 *
 * It names every server in the pool. Below Android 10 the clipboard is
 * readable by anything running in the background, and a share target is
 * whoever the user taps - so the copy button must say what it is about to
 * hand over before it hands it over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewerPrivacyTest {

    private lateinit var context: Context
    private lateinit var activity: LogViewerActivity

    private val secretish = "server ams.example:995 handshake ok"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "tiredvpn.log").writeText(secretish)
        clipboard().clearPrimaryClip()
        activity = Robolectric.buildActivity(LogViewerActivity::class.java).setup().get()
        idle()
    }

    private fun clipboard() =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun clipText() = clipboard().primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun `copying asks first`() {
        activity.findViewById<android.view.View>(R.id.copyButton).performClick()
        idle()

        assertNotNull("the user must be told what is in there", ShadowDialog.getLatestDialog())
        assertNull("nothing on the clipboard until they agree", clipText())
    }

    @Test
    fun `copying after agreeing puts the log on the clipboard`() {
        activity.findViewById<android.view.View>(R.id.copyButton).performClick()
        idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        // FileLogger keeps writing to the same file, so the copy is a superset.
        assertTrue(clipText()!!.contains(secretish))
    }

    @Test
    fun `saying no leaves the clipboard alone`() {
        activity.findViewById<android.view.View>(R.id.copyButton).performClick()
        idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()

        assertNull(clipText())
    }

    @Test
    fun `sharing asks first too`() {
        activity.findViewById<android.view.View>(R.id.shareButton).performClick()
        idle()

        assertNotNull(ShadowDialog.getLatestDialog())
        assertNull(
            "no chooser before the warning is answered",
            shadowOf(activity).peekNextStartedActivity()
        )
    }
}
