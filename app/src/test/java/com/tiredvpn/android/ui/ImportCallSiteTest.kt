package com.tiredvpn.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.tiredvpn.android.R
import com.tiredvpn.android.receiver.ConfigImportReceiver
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.VpnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * The screens, not the codec.
 *
 * A parser can be perfect and the feature still broken, because the screen hands
 * it the wrong string - the old clipboard import pulled the FIRST tired:// link
 * out of the text and threw the rest away, which no test of the parser could
 * ever notice. So every test here drives a real component with a real intent or
 * a real clipboard, taps the real confirmation button, and then asks
 * [ServerRepository] what was actually stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportCallSiteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun link(host: String, secret: String = "k") = "tired://$host:995?secret=$secret"

    private val fourLinks = (1..4).joinToString("\n") { link("n$it.example", "key-$it") }

    private fun stored() = ServerRepository.getServers(context)

    private fun setClipboard(text: String) = setClipboardItems(text)

    /**
     * A clip is a LIST of items. Copying four links can land as four items, and
     * a screen that reads item 0 alone loses three servers while still looking
     * like a successful import of one - which no single-item fixture can see.
     */
    private fun setClipboardItems(vararg items: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("config", items.first())
        items.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        clipboard.setPrimaryClip(clip)
    }

    private fun serverConfigActivity() =
        Robolectric.buildActivity(ServerConfigActivity::class.java).setup().get()

    private fun serverListActivity() =
        Robolectric.buildActivity(ServerListActivity::class.java).setup().get()

    private fun latestDialog(): AlertDialog {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("no confirmation dialog was shown", dialog)
        return dialog as AlertDialog
    }

    /**
     * A dialog button hands its click to the main looper, which Robolectric keeps
     * paused. Without this the click is a no-op and every "nothing was stored"
     * assertion below would pass for the wrong reason.
     */
    private fun clickDialogButton(which: Int) {
        latestDialog().getButton(which).performClick()
        ShadowLooper.idleMainLooper()
    }

    private fun confirm() = clickDialogButton(DialogInterface.BUTTON_POSITIVE)

    private fun cancel() = clickDialogButton(DialogInterface.BUTTON_NEGATIVE)

    private fun startImportActivity(intent: Intent) {
        Robolectric.buildActivity(ImportActivity::class.java, intent).setup()
    }

    private fun adbIntent(extra: String, value: String) =
        Intent(ImportActivity.ACTION_IMPORT_CONFIG)
            .setClass(context, ImportActivity::class.java)
            .putExtra(extra, value)

    // --- ImportActivity: the path adb can actually reach ---

    @Test
    fun `am start with a payload of four links stores four servers`() {
        // The command this replaces printed "Broadcast completed: result=0" and
        // imported nothing at all.
        startImportActivity(adbIntent(ImportActivity.EXTRA_PAYLOAD, fourLinks))
        confirm()

        assertEquals(4, stored().size)
        assertEquals(
            listOf("key-1", "key-2", "key-3", "key-4"),
            stored().sortedBy { it.serverAddress }.map { it.secret },
        )
    }

    @Test
    fun `nothing is stored until the user confirms`() {
        // This is the entire security argument for exporting the activity.
        startImportActivity(adbIntent(ImportActivity.EXTRA_PAYLOAD, fourLinks))

        assertTrue("the import wrote before the user agreed", stored().isEmpty())
    }

    @Test
    fun `cancelling the dialog stores nothing`() {
        startImportActivity(adbIntent(ImportActivity.EXTRA_PAYLOAD, fourLinks))
        cancel()

        assertTrue(stored().isEmpty())
    }

    @Test
    fun `an external import warns that the source is outside the app`() {
        startImportActivity(adbIntent(ImportActivity.EXTRA_PAYLOAD, link("n1.example")))

        val shown = ShadowDialog.getLatestDialog()
        assertNotNull(shown)
        // The dialog's message view carries the warning text.
        val message = (shown as AlertDialog).findViewById<android.widget.TextView>(
            android.R.id.message
        )?.text?.toString().orEmpty()
        assertTrue(
            "no external-source warning shown",
            message.contains(context.getString(R.string.import_external_warning)),
        )
    }

    @Test
    fun `a tired deep link opens the import and stores the server`() {
        startImportActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link("deep.example"))))
        confirm()

        assertEquals(listOf("deep.example"), stored().map { it.serverAddress })
    }

    @Test
    fun `a link shared in from another app is imported`() {
        val shared = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "here you go: ${link("shared.example")}")

        startImportActivity(shared)
        confirm()

        assertEquals(listOf("shared.example"), stored().map { it.serverAddress })
    }

    @Test
    fun `a JSON file pushed to the device is imported`() {
        // Several LINES, so a read that stops at the first line is visible.
        val file = tempFolder.newFile("pool.txt")
        file.writeText((1..3).joinToString("\n") { link("n$it.example", "key-$it") })

        startImportActivity(adbIntent(ImportActivity.EXTRA_FILE, file.absolutePath))
        confirm()

        assertEquals(3, stored().size)
    }

    @Test
    fun `the file extra cannot be aimed at our own private storage`() {
        // Otherwise any installed app could ask us to render the contents of our
        // own backup cache on screen.
        val inside = File(context.filesDir, "secrets.json").apply {
            parentFile?.mkdirs()
            writeText("""[{"server":"leak.example","port":995,"secret":"k"}]""")
        }

        val payload = ImportActivity.payloadFrom(
            context,
            adbIntent(ImportActivity.EXTRA_FILE, inside.absolutePath),
        )

        assertNull(payload)
    }

    @Test
    fun `an unreadable file path is refused rather than crashing`() {
        val payload = ImportActivity.payloadFrom(
            context,
            adbIntent(ImportActivity.EXTRA_FILE, "/nope/does/not/exist.json"),
        )

        assertNull(payload)
    }

    @Test
    fun `the activity hands the codec the whole payload, not the first link`() {
        // Guards the call site directly: payloadFrom must not pre-filter.
        val extracted = ImportActivity.payloadFrom(
            context,
            adbIntent(ImportActivity.EXTRA_PAYLOAD, fourLinks),
        )

        assertEquals(fourLinks, extracted)
        assertEquals(4, VpnConfig.extractTiredUrls(extracted).size)
    }

    @Test
    fun `base64 handed to am start is imported`() {
        val blob = android.util.Base64.encodeToString(
            fourLinks.toByteArray(),
            android.util.Base64.NO_WRAP,
        )

        startImportActivity(adbIntent(ImportActivity.EXTRA_PAYLOAD, blob))
        confirm()

        assertEquals(4, stored().size)
    }

    // --- ServerConfigActivity: the clipboard button ---

    @Test
    fun `the clipboard button imports every link in the clipboard`() {
        // The regression: this screen called extractTiredUrl (singular) and
        // imported exactly one server no matter how many were pasted.
        setClipboard(fourLinks)

        val activity = Robolectric.buildActivity(ServerConfigActivity::class.java).setup().get()
        activity.findViewById<android.view.View>(R.id.importClipboardButton).performClick()
        ShadowLooper.idleMainLooper()
        confirm()

        assertEquals(4, stored().size)
    }

    @Test
    fun `the clipboard button imports a JSON array too`() {
        setClipboard(
            (1..2).joinToString(",", "[", "]") {
                """{"server":"n$it.example","port":995,"secret":"key-$it"}"""
            }
        )

        val activity = Robolectric.buildActivity(ServerConfigActivity::class.java).setup().get()
        activity.findViewById<android.view.View>(R.id.importClipboardButton).performClick()
        ShadowLooper.idleMainLooper()
        confirm()

        assertEquals(2, stored().size)
    }

    @Test
    fun `the clipboard button does not warn about an external source`() {
        // The user pasted this themselves; the scary banner belongs on deep links.
        setClipboard(link("n1.example"))

        val activity = Robolectric.buildActivity(ServerConfigActivity::class.java).setup().get()
        activity.findViewById<android.view.View>(R.id.importClipboardButton).performClick()
        ShadowLooper.idleMainLooper()

        val message = latestDialog().findViewById<android.widget.TextView>(android.R.id.message)
            ?.text?.toString().orEmpty()
        assertFalse(message.contains(context.getString(R.string.import_external_warning)))
    }

    @Test
    fun `the clipboard button reads every item of a multi-item clip`() {
        // The clipboard holds four items here, not one string with newlines.
        setClipboardItems(*(1..4).map { link("m$it.example", "key-$it") }.toTypedArray())

        serverConfigActivity()
            .findViewById<android.view.View>(R.id.importClipboardButton).performClick()
        ShadowLooper.idleMainLooper()
        confirm()

        assertEquals(4, stored().size)
        assertEquals(
            listOf("key-1", "key-2", "key-3", "key-4"),
            stored().sortedBy { it.serverAddress }.map { it.secret },
        )
    }

    // --- ServerConfigActivity: the paste box ---

    @Test
    fun `the paste box imports every link that was typed into it`() {
        // It used to reject anything not starting with tired:// and, once that
        // was lifted, still had to hand the whole box to the codec.
        val activity = serverConfigActivity()
        activity.findViewById<android.view.View>(R.id.enterUrlButton).performClick()
        ShadowLooper.idleMainLooper()

        val input = latestDialog().window!!.decorView.findEditText()
        input!!.setText(fourLinks)
        confirm() // dismiss the paste box; the preview opens on top of it
        confirm() // and confirm the preview

        assertEquals(4, stored().size)
    }

    @Test
    fun `the paste box takes a JSON array, not only links`() {
        val activity = serverConfigActivity()
        activity.findViewById<android.view.View>(R.id.enterUrlButton).performClick()
        ShadowLooper.idleMainLooper()

        val input = latestDialog().window!!.decorView.findEditText()
        input!!.setText(
            (1..2).joinToString(",", "[", "]") {
                """{"server":"j$it.example","port":995,"secret":"key-$it"}"""
            }
        )
        confirm() // the paste box
        confirm() // the preview

        assertEquals(2, stored().size)
    }

    // --- ServerListActivity: the add button ---

    @Test
    fun `the add button imports the whole clipboard, not just the first entry`() {
        setClipboard(fourLinks)

        val activity = Robolectric.buildActivity(ServerListActivity::class.java).setup().get()
        activity.findViewById<android.view.View>(R.id.addServerButton).performClick()
        ShadowLooper.idleMainLooper()
        confirm()

        assertEquals(4, stored().size)
    }

    @Test
    fun `the add button reads every item of a multi-item clip`() {
        setClipboardItems(*(1..3).map { link("m$it.example", "key-$it") }.toTypedArray())

        serverListActivity().findViewById<android.view.View>(R.id.addServerButton).performClick()
        ShadowLooper.idleMainLooper()
        confirm()

        assertEquals(3, stored().size)
    }

    @Test
    fun `the add button falls through to manual entry when the clipboard is prose`() {
        setClipboard("nothing importable in here")

        val activity = Robolectric.buildActivity(ServerListActivity::class.java).setup().get()
        activity.findViewById<android.view.View>(R.id.addServerButton).performClick()
        ShadowLooper.idleMainLooper()

        assertNull("a dialog was shown for a clipboard with no config", ShadowDialog.getLatestDialog())
        assertTrue(stored().isEmpty())
    }

    // --- ConfigImportReceiver: same-signature automation, no UI ---

    @Test
    fun `the receiver stores four servers from one broadcast`() {
        val intent = Intent(ConfigImportReceiver.ACTION_IMPORT_CONFIG)
            .putExtra("payload", fourLinks)

        ConfigImportReceiver().onReceive(context, intent)

        assertEquals(4, stored().size)
    }

    @Test
    fun `the receiver still accepts the legacy json extra`() {
        val intent = Intent(ConfigImportReceiver.ACTION_IMPORT_CONFIG)
            .putExtra("json", """{"server":"legacy.example","port":8443,"secret":"old"}""")

        ConfigImportReceiver().onReceive(context, intent)

        assertEquals(listOf("legacy.example"), stored().map { it.serverAddress })
    }

    @Test
    fun `the receiver reads a multi-line file whole`() {
        val file = tempFolder.newFile("pool.txt")
        file.writeText((1..4).joinToString("\n") { link("r$it.example", "key-$it") })

        ConfigImportReceiver().onReceive(
            context,
            Intent(ConfigImportReceiver.ACTION_IMPORT_CONFIG).putExtra("file", file.absolutePath),
        )

        assertEquals(4, stored().size)
    }

    @Test
    fun `the receiver ignores a broadcast with another action`() {
        ConfigImportReceiver().onReceive(
            context,
            Intent("com.tiredvpn.SOMETHING_ELSE").putExtra("payload", fourLinks),
        )

        assertTrue(stored().isEmpty())
    }
}

/** Depth-first search for the first EditText inside a dialog's view tree. */
private fun android.view.View.findEditText(): android.widget.EditText? {
    if (this is android.widget.EditText) return this
    if (this is android.view.ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).findEditText()?.let { return it }
        }
    }
    return null
}
