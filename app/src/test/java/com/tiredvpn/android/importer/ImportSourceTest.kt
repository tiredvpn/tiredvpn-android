package com.tiredvpn.android.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Reading the payload IN, before any parsing happens.
 *
 * A payload truncated here is invisible to every parser test: the codec cannot
 * tell "the user copied one link" from "we read one item of a four-item clip".
 * So each test below feeds a source with several distinct pieces and asserts
 * that all of them come back, in order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun clipOf(vararg items: String): ClipData {
        val clip = ClipData.newPlainText("config", items.first())
        items.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }

    // --- clipboard ---

    @Test
    fun `every item of a multi-item clip is read, in order`() {
        // A copy of four links can arrive as four items. Reading item 0 alone
        // loses three servers and still looks like a successful import of one.
        val clip = clipOf("first", "second", "third", "fourth")

        val text = ImportSource.fromClip(context, clip)

        assertEquals("first\nsecond\nthird\nfourth", text)
    }

    @Test
    fun `a four-item clip of links yields four parsable links`() {
        val clip = clipOf(*(1..4).map { "tired://n$it.example:995?secret=key-$it" }.toTypedArray())

        val parsed = ConfigCodec.parse(ImportSource.fromClip(context, clip))

        assertEquals(4, parsed.servers.size)
        assertEquals(
            listOf("key-1", "key-2", "key-3", "key-4"),
            parsed.servers.map { it.config.secret },
        )
    }

    @Test
    fun `a single-item clip is read unchanged`() {
        assertEquals("only", ImportSource.fromClip(context, clipOf("only")))
    }

    @Test
    fun `fromClipboard reads the whole system clipboard, not one item`() {
        // Same assertion as above but through the ClipboardManager, which is what
        // the screens actually call.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clipOf("alpha", "beta", "gamma"))

        assertEquals("alpha\nbeta\ngamma", ImportSource.fromClipboard(context))
    }

    @Test
    fun `an absent clip reads as empty, not as a crash`() {
        assertEquals("", ImportSource.fromClip(context, null))
    }

    // --- files ---

    @Test
    fun `a multi-line file is read whole`() {
        // Reading the first line only would pass a single-line fixture.
        val file = tempFolder.newFile("pool.txt")
        val lines = (1..4).map { "tired://n$it.example:995?secret=key-$it" }
        file.writeText(lines.joinToString("\n"))

        val text = ImportSource.fromFile(file.absolutePath)

        assertEquals(lines.joinToString("\n"), text)
        assertEquals(4, ConfigCodec.parse(text).servers.size)
    }

    @Test
    fun `the last line of a file is not dropped`() {
        val file = tempFolder.newFile("trailing.txt")
        file.writeText("alpha\nbeta\nomega")

        assertTrue(ImportSource.fromFile(file.absolutePath)!!.endsWith("omega"))
    }

    @Test
    fun `a file inside the blocked root is refused`() {
        val blocked = tempFolder.newFolder("private")
        val file = File(blocked, "secrets.json").apply { writeText("[]") }

        assertNull(ImportSource.fromFile(file.absolutePath, blockedRoot = blocked.absolutePath))
    }

    @Test
    fun `a path that escapes into the blocked root through dot-dot is refused`() {
        // Canonicalisation, not string comparison: /tmp/x/../private/secrets.json
        // is inside private.
        val blocked = tempFolder.newFolder("private")
        val sibling = tempFolder.newFolder("public")
        File(blocked, "secrets.json").writeText("[]")
        val sneaky = "${sibling.absolutePath}/../private/secrets.json"

        assertNull(ImportSource.fromFile(sneaky, blockedRoot = blocked.absolutePath))
    }

    @Test
    fun `a file outside the blocked root is read`() {
        // Rule 2: the refusals above are worthless without this control - they
        // would also pass if fromFile refused everything.
        val blocked = tempFolder.newFolder("private")
        val file = tempFolder.newFile("outside.json")
        file.writeText("""[{"server":"a.example","port":995,"secret":"k"}]""")

        val text = ImportSource.fromFile(file.absolutePath, blockedRoot = blocked.absolutePath)

        assertEquals(1, ConfigCodec.parse(text).servers.size)
    }

    @Test
    fun `a file over the size cap is refused`() {
        val file = tempFolder.newFile("huge.json")
        file.writeText("x".repeat((ImportSource.MAX_FILE_BYTES + 1).toInt()))

        assertNull(ImportSource.fromFile(file.absolutePath))
    }

    @Test
    fun `a file at the size cap is still read`() {
        val file = tempFolder.newFile("big.json")
        file.writeText("x".repeat(ImportSource.MAX_FILE_BYTES.toInt()))

        assertEquals(ImportSource.MAX_FILE_BYTES.toInt(), ImportSource.fromFile(file.absolutePath)?.length)
    }

    @Test
    fun `a missing file and a blank path are refused without throwing`() {
        assertNull(ImportSource.fromFile("/nope/missing.json"))
        assertNull(ImportSource.fromFile(""))
        assertNull(ImportSource.fromFile(null))
    }

    @Test
    fun `a directory is not a config file`() {
        assertNull(ImportSource.fromFile(tempFolder.newFolder("adir").absolutePath))
    }

    // --- content uri (the system file picker) ---

    @Test
    fun `a picked file is read whole, every line of it`() {
        val uri = Uri.parse("content://test/backup.json")
        val body = (1..4).joinToString("\n") { "tired://n$it.example:995?secret=key-$it" }
        shadowOf(context.contentResolver).registerInputStream(uri, body.byteInputStream())

        val text = ImportSource.fromUri(context.contentResolver, uri)

        assertEquals(body, text)
        assertEquals(4, ConfigCodec.parse(text).servers.size)
    }

    @Test
    fun `an unreadable uri is refused rather than crashing`() {
        assertNull(ImportSource.fromUri(context.contentResolver, Uri.parse("content://test/gone")))
    }
}
