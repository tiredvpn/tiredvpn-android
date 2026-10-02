package com.tiredvpn.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The import confirmation must survive being covered.
 *
 * With `android:noHistory="true"` the platform finishes ImportActivity as soon
 * as anything else comes to the front. The dialog it was showing leaks
 * (WindowLeaked in ImportActivity.handle), nothing is written, and the user
 * comes back to a screen without the dialog. Reproduced on a phone by opening
 * Settings over the dialog; seen in a test run as "the first tap on Import did
 * not save".
 */
class ImportActivityManifestTest {

    private fun importActivityBlock(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/AndroidManifest.xml").isFile) dir = dir.parentFile
        val text = File(requireNotNull(dir) { "cannot locate AndroidManifest.xml" }, "src/main/AndroidManifest.xml")
            .readText()
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
        val start = text.indexOf("""android:name=".ui.ImportActivity"""")
        assertTrue("ImportActivity not found in the manifest", start >= 0)
        return text.substring(start, text.indexOf("</activity>", start))
    }

    @Test
    fun `the import activity is not finished when something covers it`() {
        val block = importActivityBlock()
        assertTrue("scanner must see the activity's attributes", block.contains("android:exported"))
        assertFalse(
            "noHistory finishes the activity under its own confirmation dialog",
            Regex("""android:noHistory\s*=\s*"true"""").containsMatchIn(block)
        )
    }
}
