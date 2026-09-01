package com.tiredvpn.android.util

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards the switch from "reopen the file on every flush" to a held-open
 * writer. Holding a handle across flushes is what removes this thread as the
 * process's most frequent caller of open(), but it introduces two ways to lose
 * the log: appending past a truncated file after rotation, and writing into an
 * unlinked inode after clear().
 */
class FileLoggerTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private lateinit var logFile: File

    @Before
    fun setUp() {
        logFile = File(tmp.newFolder(), "tiredvpn.log")
        FileLogger.init(logFile)
        flush()
    }

    /** The writer thread wakes every 100 ms; give it room without being flaky. */
    private fun flush() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(120)
            if (logFile.exists() && logFile.length() > 0) return
        }
    }

    private fun awaitContains(needle: String): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (logFile.exists() && logFile.readText().contains(needle)) return true
            Thread.sleep(120)
        }
        return false
    }

    @Test
    fun `lines reach the file`() {
        FileLogger.i("test", "first-marker-9f3a")
        assertTrue(awaitContains("first-marker-9f3a"))
    }

    /**
     * With a held-open writer, consecutive flushes must keep appending to the
     * same file rather than each landing in a fresh handle at a stale offset.
     */
    @Test
    fun `consecutive flushes all land in the file`() {
        FileLogger.i("test", "batch-a-1122")
        assertTrue(awaitContains("batch-a-1122"))
        FileLogger.i("test", "batch-b-3344")
        assertTrue(awaitContains("batch-b-3344"))
        FileLogger.i("test", "batch-c-5566")
        assertTrue(awaitContains("batch-c-5566"))

        val text = logFile.readText()
        assertTrue(text.contains("batch-a-1122"))
        assertTrue(text.contains("batch-b-3344"))
    }

    /**
     * clear() unlinks the file. A writer still holding the old inode would
     * keep writing into nothing and the log would never come back.
     */
    @Test
    fun `logging resumes after clear`() {
        FileLogger.i("test", "before-clear-aaaa")
        assertTrue(awaitContains("before-clear-aaaa"))

        FileLogger.clear()

        FileLogger.i("test", "after-clear-bbbb")
        assertTrue(awaitContains("after-clear-bbbb"))
    }

    /**
     * Regression guard only: rotation still fires and logging survives it.
     *
     * It does NOT prove that closing the handle before rotation is necessary.
     * Removing that close leaves this test green, because the stream is opened
     * in append mode and every write lands at the current end regardless of
     * truncation. Recorded here so nobody later reads this test as evidence
     * that the pre-rotation close is what keeps the file bounded.
     */
    @Test
    fun `rotation shrinks the file and logging continues`() {
        val filler = "x".repeat(500)
        repeat(3000) { FileLogger.i("test", "$it-$filler") }

        val deadline = System.currentTimeMillis() + 30_000
        var rotated = false
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            if (logFile.exists() && logFile.readText().contains("Log rotated at")) {
                rotated = true
                break
            }
        }
        assertTrue("log never rotated — cap not reached", rotated)

        FileLogger.i("test", "after-rotation-cccc")
        assertTrue(awaitContains("after-rotation-cccc"))

        // Well clear of MAX_FILE_SIZE + one flush batch: proves the handle did
        // not keep appending at the pre-rotation offset.
        assertTrue(
            "file kept growing past the cap: ${logFile.length()}",
            logFile.length() < 3_000_000
        )
    }
}
