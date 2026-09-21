package com.tiredvpn.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reading the end of a file that has no end.
 *
 * The log grows for as long as the client runs; the screen shows the last
 * thousand lines of it. readLines().takeLast(1000) got the right answer by
 * loading the whole file to throw almost all of it away.
 */
class LogTailTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun logOf(lineCount: Int, prefix: String = "line"): File {
        val file = folder.newFile()
        file.writeText((1..lineCount).joinToString("\n") { "$prefix $it" })
        return file
    }

    @Test
    fun `a short log is returned whole`() {
        val text = LogViewerActivity.tailOf(logOf(3), maxLines = 1000)

        assertEquals("line 1\nline 2\nline 3", text)
    }

    @Test
    fun `a missing or empty file reads as nothing`() {
        assertEquals("", LogViewerActivity.tailOf(File(folder.root, "absent.log"), maxLines = 1000))
        assertEquals("", LogViewerActivity.tailOf(folder.newFile(), maxLines = 1000))
    }

    @Test
    fun `a long log is cut to the last lines`() {
        val lines = LogViewerActivity.tailOf(logOf(5000), maxLines = 1000).split("\n")

        assertEquals(1000, lines.size)
        assertEquals("line 4001", lines.first())
        assertEquals("line 5000", lines.last())
    }

    @Test
    fun `the byte window never returns half a line`() {
        // A window far smaller than the file, so it is guaranteed to land in
        // the middle of one: that half line must not reach the screen.
        val text = LogViewerActivity.tailOf(logOf(400, prefix = "0123456789 line"), maxLines = 1000, windowBytes = 300)

        val lines = text.split("\n")
        assertTrue("the cut line must be dropped", lines.all { it.startsWith("0123456789 line ") })
        assertEquals("0123456789 line 400", lines.last())
        assertTrue("the window must actually have cut something", lines.size < 400)
    }

    @Test
    fun `what is read is bounded by the window, not by the file`() {
        // Lines long enough that a thousand of them do not fit in the window:
        // the byte ceiling wins over the line count, which is the difference
        // between reading the end of the file and reading all of it to keep
        // the end. ~6 MB of fixture against a 512 KB window.
        val file = logOf(10_000, prefix = "x".repeat(600) + " line")
        assertTrue("fixture must dwarf the window", file.length() > 8 * LogViewerActivity.TAIL_BYTES)

        val text = LogViewerActivity.tailOf(file, maxLines = LogViewerActivity.MAX_LINES)
        val lines = text.split("\n")

        assertTrue(
            "the read is capped by the window",
            text.toByteArray(Charsets.UTF_8).size <= LogViewerActivity.TAIL_BYTES
        )
        assertTrue(
            "a 512 KB window cannot hold 1000 lines of 600 bytes",
            lines.size < LogViewerActivity.MAX_LINES
        )
        assertTrue("the newest lines are the ones kept", lines.last().endsWith("line 10000"))
    }
}
