package com.tiredvpn.android.ui

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Restoring from a picked document.
 *
 * The picker hands back a URI, not a file the app chose - so the thing on the
 * other end can be any size at all. It used to be read whole, on the main
 * thread, and handed to the parser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RestoreFileTest {

    private lateinit var context: Context
    private lateinit var activity: SettingsActivity

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    }

    private fun document(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://documents/${bytes.size}")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    @Test
    fun `a config-sized document is read whole`() {
        val text = """[{"serverAddress":"ams.example","serverPort":995,"secret":"k"}]"""

        val picked = activity.readPickedFile(document(text.toByteArray()))

        assertEquals(SettingsActivity.PickedFile.Text(text), picked)
    }

    @Test
    fun `a document right at the ceiling is still read`() {
        val bytes = ByteArray(SettingsActivity.MAX_RESTORE_BYTES) { 'a'.code.toByte() }

        val picked = activity.readPickedFile(document(bytes))

        assertTrue(picked is SettingsActivity.PickedFile.Text)
        assertEquals(SettingsActivity.MAX_RESTORE_BYTES, (picked as SettingsActivity.PickedFile.Text).value.length)
    }

    @Test
    fun `a document past the ceiling is refused, not parsed`() {
        val bytes = ByteArray(SettingsActivity.MAX_RESTORE_BYTES + 1) { 'a'.code.toByte() }

        val picked = activity.readPickedFile(document(bytes))

        assertEquals(SettingsActivity.PickedFile.TooLarge, picked)
    }

    @Test
    fun `a document that cannot be opened says so`() {
        val picked = activity.readPickedFile(Uri.parse("content://documents/missing"))

        assertTrue(picked is SettingsActivity.PickedFile.Unreadable)
    }
}
