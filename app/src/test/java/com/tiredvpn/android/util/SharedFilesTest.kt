package com.tiredvpn.android.util

import android.content.Context
import androidx.core.content.FileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * That the files we hand to FileProvider are files FileProvider will take.
 *
 * `file_paths.xml` was narrowed from the whole cache directory to two
 * subdirectories without moving anything into them, so both call sites kept
 * writing into the root of `cacheDir`. `getUriForFile` answers that with an
 * IllegalArgumentException, which killed the config backup outright — the only
 * way to carry servers to a new phone — and quietly demoted the log share to a
 * plain-text message while the dialog still said file.
 *
 * So the assertion is the real call, not a string comparison of paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedFilesTest {

    private lateinit var context: Context

    /**
     * FileProvider memoises one PathStrategy per authority in a private static
     * map, and the roots inside it are absolute paths. Robolectric hands every
     * test method its own temporary data directory, so a strategy built during
     * one method names directories that do not exist during the next, and every
     * call after the first fails with "Failed to find configured root" no matter
     * what the code under test does. Cleared here so each method builds its own.
     */
    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        val field = FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as MutableMap<*, *>).clear()
    }

    private fun authority() = "${context.packageName}.fileprovider"

    private fun uriFor(file: File) = FileProvider.getUriForFile(context, authority(), file)

    // --- positive control (rule 2): the check can fail -----------------------

    @Test
    fun `a file outside the declared roots is refused`() {
        val stray = File(context.cacheDir, "tiredvpn-backup.json").apply {
            parentFile?.mkdirs()
            writeText("{}")
        }
        val thrown = try {
            uriFor(stray)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertNotNull(
            "if this passes, the test cannot tell a granted path from an ungranted one " +
                "and proves nothing about the two below",
            thrown
        )
    }

    @Test
    fun `the exported config backup can be handed out`() {
        val file = SharedFiles.file(context, "tiredvpn-backup.json").apply { writeText("[]") }
        val uri = uriFor(file)
        assertEquals(authority(), uri.authority)
    }

    @Test
    fun `the shared log file can be handed out`() {
        val file = SharedFiles.file(context, "tiredvpn_logs.txt").apply { writeText("log line") }
        val uri = uriFor(file)
        assertEquals(authority(), uri.authority)
    }

    @Test
    fun `the directory exists by the time a file is asked for`() {
        SharedFiles.dir(context).delete()
        val file = SharedFiles.file(context, "x.txt")
        assertTrue("writeText would fail on a missing parent", file.parentFile!!.isDirectory)
        file.writeText("x")
        assertTrue(file.exists())
    }

    /**
     * The downloaded APK goes through the same provider from its own root.
     * Named here so that narrowing the roots again cannot forget it.
     */
    @Test
    fun `the update directory is granted too`() {
        val file = File(context.cacheDir, "updates/app.apk").apply {
            parentFile?.mkdirs()
            writeText("apk")
        }
        assertEquals(authority(), uriFor(file).authority)
    }
}
