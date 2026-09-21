package com.tiredvpn.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A configuration variant of a layout has to declare the same ids as its base.
 *
 * View binding merges every variant of one layout name into one class, and an
 * id that exists in some configurations and not others simply becomes a
 * nullable field. So a view deleted from `layout/activity_main.xml` and left in
 * `layout-television/activity_main.xml` costs nothing at build time and nothing
 * at runtime on a phone — it just sits there, referenced by no code, until
 * somebody reading the TV layout concludes the feature exists. That is how
 * `connectionInfo` and `ipAddressText` outlived the code that filled them.
 *
 * The rule is symmetric on purpose: a view present only in the base layout is
 * the same drift seen from the other side, and on a TV it is a NullPointer
 * waiting for the first `binding.thing.foo` written without a `?`.
 */
class LayoutVariantTest {

    private fun resRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/res").isDirectory) dir = dir.parentFile
        return File(requireNotNull(dir) { "cannot locate src/main/res" }, "src/main/res")
    }

    private fun idsOf(file: File): Set<String> =
        Regex("""android:id="@\+id/([A-Za-z0-9_]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()

    private fun variantDirs(): List<File> =
        resRoot().listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("layout-") }
            ?.sortedBy { it.name }
            ?: emptyList()

    // --- positive control (rule 2) ------------------------------------------

    @Test
    fun `there are variant layouts to compare and ids to find in them`() {
        val variants = variantDirs()
        assertTrue("no layout- variant directories found; the scan below asserts nothing", variants.isNotEmpty())

        val pairs = variants.flatMap { dir ->
            dir.listFiles { f -> f.extension == "xml" }.orEmpty()
                .filter { File(resRoot(), "layout/${it.name}").isFile }
        }
        assertTrue("no variant layout has a base to compare against", pairs.isNotEmpty())

        val base = File(resRoot(), "layout/activity_main.xml")
        assertTrue("the main layout is missing", base.isFile)
        assertTrue("no ids parsed out of the main layout - the regex is broken", idsOf(base).size > 3)
    }

    // --- the rule -----------------------------------------------------------

    @Test
    fun `every layout variant declares the same ids as its base`() {
        for (dir in variantDirs()) {
            for (variant in dir.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }) {
                val base = File(resRoot(), "layout/${variant.name}")
                if (!base.isFile) continue

                val baseIds = idsOf(base)
                val variantIds = idsOf(variant)

                assertEquals(
                    "${dir.name}/${variant.name} declares ids the base layout does not: " +
                        "view binding makes these nullable and no code can rely on them",
                    emptySet<String>(), variantIds - baseIds
                )
                assertEquals(
                    "layout/${variant.name} declares ids ${dir.name} does not: " +
                        "code written against the base will find null on that configuration",
                    emptySet<String>(), baseIds - variantIds
                )
            }
        }
    }
}
