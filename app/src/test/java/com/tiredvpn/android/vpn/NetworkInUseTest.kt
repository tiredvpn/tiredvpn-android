package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deciding by the network that carries traffic, not by the last callback.
 *
 * The scenario that produced the defect, in order: Wi-Fi comes up and carries
 * everything; the modem registers and LTE arrives as a second validated
 * network; Wi-Fi then drops. With one field written by the last `onAvailable`,
 * the field said LTE, so Wi-Fi's own loss was filed as "not the one in use"
 * and ignored, and the reconnect waited for the two-second poll.
 */
class NetworkInUseTest {

    private val wifi = "net-wifi"
    private val lte = "net-lte"
    private val lte2 = "net-lte-2"
    private val eth = "net-eth"

    private fun tracker() = NetworkInUse<String>()

    private fun NetworkInUse<String>.up(
        network: String,
        kind: NetworkInUse.Kind,
        validated: Boolean = true,
    ) = available(network, kind, validated)

    // --- ranking ---

    @Test
    fun `the first network to come up is the one in use`() {
        val t = tracker()

        assertEquals(NetworkInUse.Change.SWITCHED, t.up(wifi, NetworkInUse.Kind.WIFI))
        assertEquals(wifi, t.inUse)
        assertEquals(NetworkInUse.Kind.WIFI, t.inUseKind)
    }

    @Test
    fun `cellular registering behind live wifi does not move traffic`() {
        val t = tracker()
        t.up(wifi, NetworkInUse.Kind.WIFI)

        val change = t.up(lte, NetworkInUse.Kind.CELLULAR)

        assertEquals(NetworkInUse.Change.NONE, change)
        assertEquals("wifi outranks cellular, so the later callback must not win", wifi, t.inUse)
        assertEquals(2, t.size)
    }

    @Test
    fun `wifi appearing behind cellular does move traffic`() {
        val t = tracker()
        t.up(lte, NetworkInUse.Kind.CELLULAR)

        val change = t.up(wifi, NetworkInUse.Kind.WIFI)

        assertEquals(NetworkInUse.Change.SWITCHED, change)
        assertEquals(wifi, t.inUse)
    }

    @Test
    fun `ethernet outranks wifi`() {
        val t = tracker()
        t.up(wifi, NetworkInUse.Kind.WIFI)

        assertEquals(NetworkInUse.Change.SWITCHED, t.up(eth, NetworkInUse.Kind.ETHERNET))
        assertEquals(eth, t.inUse)
    }

    @Test
    fun `an unvalidated network never outranks a validated one`() {
        val t = tracker()
        t.up(lte, NetworkInUse.Kind.CELLULAR, validated = true)

        // Ethernet would win on transport alone; a captive portal it is not
        // through yet is not a network traffic can use.
        assertEquals(NetworkInUse.Change.NONE, t.up(eth, NetworkInUse.Kind.ETHERNET, validated = false))
        assertEquals(lte, t.inUse)

        // It validates: now it wins.
        assertEquals(NetworkInUse.Change.SWITCHED, t.up(eth, NetworkInUse.Kind.ETHERNET, validated = true))
        assertEquals(eth, t.inUse)
    }

    @Test
    fun `an equal second network leaves the incumbent alone`() {
        val t = tracker()
        t.up(lte, NetworkInUse.Kind.CELLULAR)

        assertEquals(
            "a rule that changes its mind on a tie is a reconnect generator",
            NetworkInUse.Change.NONE,
            t.up(lte2, NetworkInUse.Kind.CELLULAR),
        )
        assertEquals(lte, t.inUse)
    }

    // --- loss ---

    @Test
    fun `losing the idle background link is not an event`() {
        val t = tracker()
        t.up(wifi, NetworkInUse.Kind.WIFI)
        t.up(lte, NetworkInUse.Kind.CELLULAR)

        assertEquals(NetworkInUse.Change.NONE, t.lost(lte))
        assertEquals(wifi, t.inUse)
    }

    @Test
    fun `losing the link in use while another is up is a switch, not an outage`() {
        val t = tracker()
        t.up(wifi, NetworkInUse.Kind.WIFI)
        t.up(lte, NetworkInUse.Kind.CELLULAR)

        // This is the case the old bookkeeping got backwards.
        assertEquals(NetworkInUse.Change.SWITCHED, t.lost(wifi))
        assertEquals(lte, t.inUse)
        assertEquals(NetworkInUse.Kind.CELLULAR, t.inUseKind)
    }

    @Test
    fun `losing the last link is an outage`() {
        val t = tracker()
        t.up(wifi, NetworkInUse.Kind.WIFI)

        assertEquals(NetworkInUse.Change.LOST, t.lost(wifi))
        assertNull(t.inUse)
        assertEquals(0, t.size)
    }

    @Test
    fun `losing a network that was never up changes nothing`() {
        val t = tracker()
        t.up(wifi, NetworkInUse.Kind.WIFI)

        assertEquals(NetworkInUse.Change.NONE, t.lost(lte))
        assertEquals(wifi, t.inUse)
    }

    @Test
    fun `clear forgets everything`() {
        val t = tracker()
        t.up(wifi, NetworkInUse.Kind.WIFI)
        t.clear()

        assertNull(t.inUse)
        assertEquals(0, t.size)
    }

    // --- the vocabulary the two producers share ---

    @Test
    fun `transport names match the ones NetworkTransition is fed by the poll`() {
        // The poll spells them "wifi", "mobile", "ethernet"; the reason string
        // is built from those words. A second producer with its own spelling
        // would hand the core an empty reason and nobody would notice.
        assertEquals(NetworkTransition.WIFI, NetworkInUse.Kind.WIFI.wireName)
        assertEquals(NetworkTransition.MOBILE, NetworkInUse.Kind.CELLULAR.wireName)
        assertEquals("ethernet", NetworkInUse.Kind.ETHERNET.wireName)

        assertEquals(
            "wifi_to_lte",
            NetworkTransition.reason(
                NetworkInUse.Kind.WIFI.wireName,
                NetworkInUse.Kind.CELLULAR.wireName,
            ),
        )
    }

    @Test
    fun `kindOf ranks the transports the way the capabilities report them`() {
        assertEquals(
            NetworkInUse.Kind.ETHERNET,
            NetworkInUse.kindOf(ethernet = true, wifi = true, cellular = false),
        )
        assertEquals(
            NetworkInUse.Kind.WIFI,
            NetworkInUse.kindOf(ethernet = false, wifi = true, cellular = true),
        )
        assertEquals(
            NetworkInUse.Kind.CELLULAR,
            NetworkInUse.kindOf(ethernet = false, wifi = false, cellular = true),
        )
        assertEquals(
            NetworkInUse.Kind.OTHER,
            NetworkInUse.kindOf(ethernet = false, wifi = false, cellular = false),
        )
    }

    // --- the call sites ---

    private fun service(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory) dir = dir.parentFile
        return File(
            requireNotNull(dir),
            "src/main/java/com/tiredvpn/android/vpn/TiredVpnService.kt",
        ).readText()
    }

    @Test
    fun `the loss policy is asked about the network in use`() {
        val text = service()

        assertTrue(
            "NetworkLossPolicy itself was right; what it was handed was not",
            text.contains("NetworkLossPolicy.isRelevant(network, inUseBefore, checkNetworkAvailability())"),
        )
        assertFalse(
            "the single last-onAvailable field is what this replaces",
            Regex("""private var currentNetwork: Network\?""").containsMatchIn(text),
        )
    }

    @Test
    fun `link properties are only believed for the network in use`() {
        val text = service()

        assertTrue(
            "recording a background link's addresses is how an idle interface " +
                "coming up read as the wifi address changing",
            text.contains("if (network == networksUp.inUse) {"),
        )
        assertTrue(
            "and the fingerprint taken on onAvailable is guarded the same way",
            text.contains("if (network == inUse) try {"),
        )
    }
}
