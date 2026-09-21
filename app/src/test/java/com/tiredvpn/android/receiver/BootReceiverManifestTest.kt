package com.tiredvpn.android.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What an exported, permission-less receiver is allowed to listen for.
 *
 * `BootReceiver` is `exported="true"` with no `android:permission` — it has to
 * be, to hear the boot broadcast — so every action in its filter is an entry
 * point for any app on the device that knows the class name. That is only safe
 * for actions on the platform's `<protected-broadcast>` list, which only the
 * system may send.
 *
 * `QUICKBOOT_POWERON` is not on that list in either spelling. Dropping the
 * `com.htc.` one and keeping `android.intent.action.QUICKBOOT_POWERON` left the
 * vector exactly where it was and added a comment saying it was closed:
 * `handleBootEvent` starts the VPN whenever there is a valid server and the
 * permission has been granted once, which is not a thing a stranger's broadcast
 * should be able to decide.
 *
 * Checked against the manifest text because this is a manifest rule; the list
 * below is from `frameworks/base/core/res/AndroidManifest.xml`.
 */
class BootReceiverManifestTest {

    /**
     * Actions this receiver may legitimately declare. Everything here appears
     * in the platform's protected-broadcast list.
     */
    private val protectedActions = setOf(
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.REBOOT",
        "android.intent.action.MY_PACKAGE_REPLACED",
        "android.intent.action.ACTION_SHUTDOWN",
        "android.intent.action.USER_UNLOCKED",
    )

    private fun manifest(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/AndroidManifest.xml").isFile) dir = dir.parentFile
        return File(requireNotNull(dir) { "cannot locate AndroidManifest.xml" }, "src/main/AndroidManifest.xml")
            .readText()
    }

    /** The receiver block, comments stripped, up to its closing tag. */
    private fun bootReceiverBlock(): String {
        val text = manifest()
        val start = text.indexOf("""android:name=".receiver.BootReceiver"""")
        assertTrue("BootReceiver not found in the manifest", start >= 0)
        val end = text.indexOf("</receiver>", start)
        assertTrue("BootReceiver has no closing tag", end > start)
        return text.substring(start, end).replace(Regex("<!--[\\s\\S]*?-->"), " ")
    }

    private fun declaredActions(block: String): List<String> =
        Regex("""<action\s+android:name="([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList()

    // --- positive control (rule 2) ------------------------------------------

    @Test
    fun `the scan reads the real manifest and sees the real filter`() {
        val block = bootReceiverBlock()
        assertTrue("the receiver must still be exported - it has to hear the boot broadcast", block.contains("""android:exported="true""""))
        val actions = declaredActions(block)
        assertTrue("no actions found - the scan is broken, not the manifest", actions.isNotEmpty())
        assertTrue(
            "the boot action itself must be there or this receiver does nothing",
            "android.intent.action.BOOT_COMPLETED" in actions
        )
        assertFalse(
            "the comment stripper must remove prose, or a retired action mentioned in a comment passes as declared",
            bootReceiverBlock().contains("used to be here")
        )
    }

    // --- the rule -----------------------------------------------------------

    @Test
    fun `every action on the exported boot receiver is a protected broadcast`() {
        val unprotected = declaredActions(bootReceiverBlock()).filter { it !in protectedActions }
        assertEquals(
            "an exported receiver with no android:permission may only listen for actions " +
                "the system alone can send; these are not on the protected-broadcast list",
            emptyList<String>(),
            unprotected
        )
    }

    @Test
    fun `QUICKBOOT_POWERON is gone in both spellings`() {
        val block = bootReceiverBlock()
        assertFalse(
            "android.intent.action.QUICKBOOT_POWERON is a vendor convention, not a protected broadcast",
            block.contains("QUICKBOOT_POWERON")
        )
    }

    /**
     * The two receivers that do carry a permission are the contrast: if
     * BootReceiver ever grows one, the rule above can be relaxed deliberately
     * rather than by accident.
     */
    @Test
    fun `the receivers that take commands are permission-gated`() {
        val text = manifest()
        for (name in listOf(".receiver.ConfigImportReceiver", ".receiver.VpnControlReceiver")) {
            val start = text.indexOf("""android:name="$name"""")
            assertTrue("$name not found", start >= 0)
            val block = text.substring(start, text.indexOf("</receiver>", start))
            assertTrue("$name must require a permission", block.contains("android:permission="))
        }
    }
}
