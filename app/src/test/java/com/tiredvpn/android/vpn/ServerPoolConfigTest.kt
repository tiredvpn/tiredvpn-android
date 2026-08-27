package com.tiredvpn.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The generated file is the only thing standing between the app and a core
 * that silently falls back to one server, so the assertions here are on the
 * exact keys and spellings the core parses, not on "it produced some TOML".
 */
class ServerPoolConfigTest {

    private fun server(
        id: String,
        name: String = "srv-$id",
        address: String = "198.51.100.1",
        port: Int = 995,
        secret: String = "shared-secret",
        addressV6: String = "",
        preferIpv6: Boolean = false,
        fallbackV4: Boolean = true
    ) = VpnConfig(
        id = id,
        name = name,
        serverAddress = address,
        serverPort = port,
        secret = secret,
        serverAddressV6 = addressV6,
        preferIpv6 = preferIpv6,
        fallbackV4 = fallbackV4
    )

    // --- pool selection ---

    @Test
    fun `pool is the active server first then everyone sharing its secret`() {
        val a = server("a")
        val b = server("b")
        val other = server("c", secret = "another-secret")
        val pool = ServerPoolConfig.selectPool(listOf(other, a, b), a)

        assertEquals(listOf("a", "b"), pool.map { it.id })
    }

    @Test
    fun `a unique secret yields a pool of one`() {
        val a = server("a", secret = "mine")
        val pool = ServerPoolConfig.selectPool(listOf(a, server("b", secret = "theirs")), a)

        assertEquals(listOf("a"), pool.map { it.id })
    }

    @Test
    fun `a blank secret does not match other blank secrets`() {
        val a = server("a", secret = "")
        val pool = ServerPoolConfig.selectPool(listOf(a, server("b", secret = "")), a)

        assertEquals(listOf("a"), pool.map { it.id })
    }

    // --- v6 endpoint parsing ---

    @Test
    fun `bracketed v6 with a port splits into host and port`() {
        assertEquals(
            ServerPoolConfig.HostPort("2001:db8::1", 995),
            ServerPoolConfig.parseHostPort("[2001:db8::1]:995", 443)
        )
    }

    @Test
    fun `bare v6 literal keeps the default port and is not split on a hextet`() {
        assertEquals(
            ServerPoolConfig.HostPort("2001:db8::1", 443),
            ServerPoolConfig.parseHostPort("2001:db8::1", 443)
        )
    }

    @Test
    fun `hostname with a port splits normally`() {
        assertEquals(
            ServerPoolConfig.HostPort("vpn.example.org", 8443),
            ServerPoolConfig.parseHostPort("vpn.example.org:8443", 443)
        )
    }

    @Test
    fun `broken v6 strings are rejected rather than half-parsed`() {
        assertNull(ServerPoolConfig.parseHostPort("", 443))
        assertNull(ServerPoolConfig.parseHostPort("   ", 443))
        assertNull(ServerPoolConfig.parseHostPort("[2001:db8::1", 443))
        assertNull(ServerPoolConfig.parseHostPort("[]:995", 443))
        assertNull(ServerPoolConfig.parseHostPort("[2001:db8::1]995", 443))
        assertNull(ServerPoolConfig.parseHostPort("[2001:db8::1]:notaport", 443))
        assertNull(ServerPoolConfig.parseHostPort("[2001:db8::1]:70000", 443))
        assertNull(ServerPoolConfig.parseHostPort("host:0", 443))
        assertNull(ServerPoolConfig.parseHostPort(":995", 443))
        assertNull(ServerPoolConfig.parseHostPort("host:", 443))
    }

    // --- entries ---

    @Test
    fun `entry without v6 carries no v6 keys`() {
        val entries = ServerPoolConfig.entries(listOf(server("a")))

        assertEquals(1, entries.size)
        assertEquals("198.51.100.1", entries[0].address)
        assertEquals(995, entries[0].port)
        assertEquals("", entries[0].addressV6)

        val toml = ServerPoolConfig.render(entries, server("a"))
        assertTrue(toml, !toml.contains("address_v6"))
        assertTrue(toml, !toml.contains("port_v6"))
    }

    @Test
    fun `a broken v6 string drops only the v6 keys, the entry survives`() {
        val entries = ServerPoolConfig.entries(listOf(server("a", addressV6 = "[2001:db8::1")))

        assertEquals(1, entries.size)
        assertEquals("198.51.100.1", entries[0].address)
        assertEquals("", entries[0].addressV6)
    }

    @Test
    fun `resolved addresses replace the configured hostname`() {
        val entries = ServerPoolConfig.entries(
            listOf(server("a", address = "vpn.example.org")),
            mapOf("a" to "203.0.113.7:995")
        )

        assertEquals("203.0.113.7", entries[0].address)
        assertEquals(995, entries[0].port)
    }

    @Test
    fun `duplicate names are made unique because the core rejects duplicates`() {
        val entries = ServerPoolConfig.entries(
            listOf(server("a", name = "Server"), server("b", name = "Server"), server("c", name = "Server"))
        )

        assertEquals(listOf("Server", "Server-2", "Server-3"), entries.map { it.name })
    }

    // --- family table ---

    @Test
    fun `family follows the core's legacy table when a v6 endpoint exists`() {
        val v6 = listOf(ServerPoolConfig.Entry("n", "198.51.100.1", 995, "2001:db8::1", 995))

        assertEquals("prefer_v6", ServerPoolConfig.familyFor(v6, server("a", preferIpv6 = true, fallbackV4 = true)))
        assertEquals("v6_only", ServerPoolConfig.familyFor(v6, server("a", preferIpv6 = true, fallbackV4 = false)))
        assertEquals("v4_only", ServerPoolConfig.familyFor(v6, server("a", preferIpv6 = false, fallbackV4 = true)))
        assertEquals("v4_only", ServerPoolConfig.familyFor(v6, server("a", preferIpv6 = false, fallbackV4 = false)))
    }

    @Test
    fun `family is v4_only when no entry has a v6 address, whatever the flags say`() {
        val v4 = listOf(ServerPoolConfig.Entry("n", "198.51.100.1", 995))

        assertEquals("v4_only", ServerPoolConfig.familyFor(v4, server("a", preferIpv6 = true, fallbackV4 = true)))
        assertEquals("v4_only", ServerPoolConfig.familyFor(v4, server("a", preferIpv6 = true, fallbackV4 = false)))
    }

    // --- rendered document ---

    @Test
    fun `a two server pool renders both entries and the selection block`() {
        val a = server("a", name = "ams", address = "198.51.100.1", addressV6 = "[2001:db8::1]:995")
        val b = server("b", name = "fra", address = "198.51.100.2", port = 443)
        val entries = ServerPoolConfig.entries(ServerPoolConfig.selectPool(listOf(a, b), a))

        assertEquals(
            """
            # Generated by TiredVPN on every connect. Edits are lost.
            # Endpoint pool for the core's automatic failover.

            [[servers]]
            name = "ams"
            address = "198.51.100.1"
            port = 995
            address_v6 = "2001:db8::1"
            port_v6 = 995

            [[servers]]
            name = "fra"
            address = "198.51.100.2"
            port = 443

            [selection]
            policy = "priority"
            family = "v4_only"
            failure_threshold = 2
            cooldown = "1m"
            max_cooldown = "30m"
            min_dwell = "5m"

            """.trimIndent(),
            ServerPoolConfig.render(entries, a)
        )
    }

    @Test
    fun `the secret never reaches the file`() {
        val a = server("a", secret = "s3cr3t-value")
        val toml = ServerPoolConfig.render(ServerPoolConfig.entries(listOf(a)), a)

        assertTrue(toml, !toml.contains("s3cr3t-value"))
        assertTrue(toml, !toml.contains("secret"))
    }

    @Test
    fun `health_check stays out of the file`() {
        val a = server("a")
        val toml = ServerPoolConfig.render(ServerPoolConfig.entries(listOf(a)), a)

        assertTrue(toml, !toml.contains("health_check"))
        assertTrue(toml, !toml.contains("recheck_interval"))
    }

    @Test
    fun `a name with a quote is escaped instead of breaking the document`() {
        val a = server("a", name = """my "home" \ box""")
        val toml = ServerPoolConfig.render(ServerPoolConfig.entries(listOf(a)), a)

        assertTrue(toml, toml.contains("""name = "my \"home\" \\ box""""))
    }

    @Test
    fun `a name with a newline is escaped instead of breaking the document`() {
        val a = server("a", name = "line\nbreak")
        val toml = ServerPoolConfig.render(ServerPoolConfig.entries(listOf(a)), a)

        assertTrue(toml, toml.contains("""name = "line\nbreak""""))
    }

    // --- file handling ---

    @Test
    fun `write lands owner-only in the given directory and delete removes it`() {
        val dir = Files.createTempDirectory("pool").toFile()
        try {
            val file = ServerPoolConfig.write(dir, "x = 1\n")

            assertEquals(File(dir, ServerPoolConfig.FILE_NAME), file)
            assertEquals("x = 1\n", file.readText())
            assertEquals(setOf<java.nio.file.attribute.PosixFilePermission>(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            ), Files.getPosixFilePermissions(file.toPath()))

            ServerPoolConfig.delete(dir)
            assertTrue(!file.exists())
            // Idempotent: the file may never have been written.
            ServerPoolConfig.delete(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
