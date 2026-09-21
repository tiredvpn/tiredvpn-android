package com.tiredvpn.android.vpn

import com.tiredvpn.android.importer.ConfigCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Port hopping is gone from the client, and old payloads still import.
 *
 * The feature never worked: the service wrote a `port_hop` command the core
 * does not implement, and 1.10.0 cut that path. What was left was a settings
 * screen with a range, an interval, a strategy and a seed, all of which the
 * user could set and none of which reached anything. A control that does
 * nothing is worse than a missing one, because it is also a promise.
 *
 * Two properties, and they pull in opposite directions:
 *
 *  - nothing in the app offers, stores or serialises the setting any more;
 *  - a payload written by a version that did - a backup, an exported link, a
 *    subscription blob - still imports, with the hop fields ignored rather
 *    than rejected. That is what makes the removal invisible to users who
 *    already have such a file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PortHoppingRemovedTest {

    private fun moduleRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "cannot locate src/main from ${File("").absolutePath}" }
    }

    private fun mainFiles(vararg extensions: String): List<File> =
        File(moduleRoot(), "src/main").walkTopDown()
            .filter { it.isFile && extensions.any { ext -> it.name.endsWith(ext) } }
            .toList()

    @Test
    fun `no kotlin source mentions port hopping any more`() {
        val offenders = mainFiles(".kt").filter { file ->
            val text = file.readText()
            text.contains("portHop", ignoreCase = true) ||
                text.contains("PortHopper") ||
                text.contains("HopStrategy") ||
                text.contains("port_hop")
        }
        assertEquals(
            "port hopping is removed from the client; these files still carry it",
            emptyList<String>(),
            offenders.map { it.name },
        )
    }

    @Test
    fun `the port hopper class and its manager are gone`() {
        val leftovers = mainFiles(".kt")
            .map { it.name }
            .filter { it == "PortHopper.kt" || it == "ConnectionManager.kt" }
        assertEquals(
            "ConnectionManager existed only to drive PortHopper",
            emptyList<String>(),
            leftovers,
        )
    }

    @Test
    fun `no layout or string resource offers the setting`() {
        val offenders = mainFiles(".xml").filter { file ->
            val text = file.readText()
            text.contains("portHopping") || text.contains("port_hop")
        }
        assertEquals(
            "a settings row the user can still tap is the defect, not a leftover",
            emptyList<String>(),
            offenders.map { it.name },
        )
    }

    @Test
    fun `a stored config written by an older version still loads`() {
        val json = JSONObject(
            """
            {"id":"old-1","name":"Legacy","serverAddress":"1.2.3.4","serverPort":995,
             "secret":"s3cret","portHoppingEnabled":true,"portHopRangeStart":48000,
             "portHopRangeEnd":60000,"portHopIntervalMs":30000,"portHopStrategy":"fibonacci",
             "portHopSeed":"deadbeef","customDns":"9.9.9.9"}
            """.trimIndent()
        )

        val config = VpnConfig.fromJson(json)

        assertEquals("1.2.3.4", config.serverAddress)
        assertEquals(995, config.serverPort)
        assertEquals("s3cret", config.secret)
        // The fields that were not about port hopping survive the same payload.
        assertEquals("9.9.9.9", config.customDns)
        assertTrue(config.isValid)
    }

    @Test
    fun `a link written by an older version still imports`() {
        val parsed = ConfigCodec.parse(
            "tired://1.2.3.4:995?secret=s3cret&name=Legacy&hop=true&hopStart=48000" +
                "&hopEnd=60000&hopInterval=30000&hopStrategy=fibonacci&hopSeed=deadbeef&dns=9.9.9.9"
        )

        assertEquals(emptyList<ConfigCodec.Skipped>(), parsed.skipped)
        assertEquals(1, parsed.servers.size)
        val config = parsed.servers.single().config
        assertEquals("Legacy", config.name)
        assertEquals("1.2.3.4", config.serverAddress)
        assertEquals("s3cret", config.secret)
        assertEquals("9.9.9.9", config.customDns)
    }

    @Test
    fun `a json payload with hop fields imports with them ignored`() {
        val parsed = ConfigCodec.parse(
            """{"server":"5.6.7.8","port":993,"secret":"abc","hop":true,"hop_start":40000,
               "hop_strategy":"sequential","prefer_ipv6":true}"""
        )

        assertEquals(1, parsed.servers.size)
        val config = parsed.servers.single().config
        assertEquals("5.6.7.8", config.serverAddress)
        assertTrue(config.preferIpv6)
    }

    @Test
    fun `a shared link carries no hop parameters`() {
        val url = VpnConfig(
            name = "Home",
            serverAddress = "1.2.3.4",
            serverPort = 995,
            secret = "s3cret",
        ).toUrl()

        assertFalse("hop parameters must not be minted any more", url.contains("hop"))
        assertEquals("the link must still round-trip", "s3cret", VpnConfig.fromUrl(url)?.secret)
    }
}
