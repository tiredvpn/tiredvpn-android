package com.tiredvpn.android.vpn

import java.io.File

/**
 * Builds the `[[servers]]` / `[selection]` TOML the core reads through `-config`.
 *
 * The core has carried an endpoint pool with automatic failover since 1.5, but
 * the only way in is a config file: there is no flag that takes more than one
 * server. So the app writes a small file instead of teaching the core a new
 * flag.
 *
 * Two facts from the core shape everything here:
 *
 *  - One secret for the whole pool. `internal/client/endpoints.go`
 *    (reconcileSecrets) rejects a list whose entries disagree, because a
 *    strategy bakes the secret in when it is built and cannot swap it on a
 *    switch. Hence [selectPool]: the pool is the active server plus every other
 *    server that shares its secret, and nothing else.
 *  - `-server` collapses the list. The JNI arg parser sets `sawServerFlag` and
 *    calls `collapseServers(...)`, silently reducing the pool to one entry. A
 *    caller passing this file MUST drop `-server` and `-server-v6`.
 *
 * Pure Kotlin with no Android types, so the generated text can be checked on
 * the JVM - the same reason [DualStackPlan] and [Ipv6Guard] live apart from
 * [TiredVpnService].
 */
object ServerPoolConfig {

    /** Name of the generated file inside the app's private `filesDir`. */
    const val FILE_NAME = "pool.toml"

    // Selection tuning, copied from configs/client.example.toml rather than
    // invented here, so a change in the core's documented defaults is a
    // one-line diff against a known source.
    private const val POLICY = "priority"
    private const val FAILURE_THRESHOLD = 2
    private const val COOLDOWN = "1m"
    private const val MAX_COOLDOWN = "30m"
    private const val MIN_DWELL = "5m"

    /** One `[[servers]]` element, already split into the keys the core expects. */
    data class Entry(
        val name: String,
        /** IPv4 / hostname transport address, "" when the entry is v6-only. */
        val address: String,
        val port: Int,
        /** Unbracketed IPv6 literal, "" when the entry has no v6 endpoint. */
        val addressV6: String = "",
        val portV6: Int = 0
    )

    /** A host with its port, after splitting an "address:port" string. */
    data class HostPort(val host: String, val port: Int)

    /**
     * The servers the core is allowed to switch between: [active] first
     * (the priority policy dials in list order), then every other server
     * carrying the same secret, in repository order.
     *
     * A blank secret is not a match for anything - it means the server is not
     * configured yet, not that it belongs to every pool.
     */
    fun selectPool(servers: List<VpnConfig>, active: VpnConfig): List<VpnConfig> {
        if (active.secret.isBlank()) return listOf(active)
        val rest = servers.filter { it.id != active.id && it.secret == active.secret }
        return listOf(active) + rest
    }

    /**
     * Split an "address:port" string. Handles the bracketed IPv6 form and a
     * bare IPv6 literal; returns null when the string cannot be read, so a
     * caller drops the field rather than writing something the core rejects.
     *
     * @param defaultPort used when the string carries no port of its own
     */
    fun parseHostPort(raw: String, defaultPort: Int): HostPort? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close <= 1) return null
            val host = s.substring(1, close)
            val rest = s.substring(close + 1)
            if (rest.isEmpty()) return HostPort(host, defaultPort)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            return if (port in 1..65535) HostPort(host, port) else null
        }

        val colons = s.count { it == ':' }
        // Two or more colons and no brackets can only be a bare IPv6 literal:
        // splitting on the last colon would eat a hextet.
        if (colons != 1) {
            return if (s.endsWith(":")) null else HostPort(s, defaultPort)
        }

        val host = s.substringBefore(':')
        if (host.isEmpty()) return null
        val port = s.substringAfter(':').toIntOrNull() ?: return null
        return if (port in 1..65535) HostPort(host, port) else null
    }

    /**
     * Turn the pool into `[[servers]]` entries.
     *
     * @param resolvedById pre-resolved "ip:port" per server id. The TUN path
     *   resolves hostnames before the tunnel exists, because afterwards DNS
     *   goes through a tunnel that is - by the time a failover matters -
     *   exactly the thing that stopped working. Absent id means "use the
     *   configured address as written".
     *
     * Entries with no usable address at all are dropped: the core errors out on
     * one, which would cost the user the whole connection over a stale list
     * item. Names are made unique because the core rejects duplicates.
     */
    fun entries(pool: List<VpnConfig>, resolvedById: Map<String, String> = emptyMap()): List<Entry> {
        val used = HashSet<String>()
        val out = ArrayList<Entry>(pool.size)

        for (server in pool) {
            val v4 = resolvedById[server.id]
                ?.let { parseHostPort(it, server.serverPort) }
                ?: parseHostPort(server.serverAddress, server.serverPort)
            val v6 = parseHostPort(server.serverAddressV6, server.serverPort)
            if (v4 == null && v6 == null) continue

            out.add(
                Entry(
                    name = uniqueName(server, v4?.host ?: v6!!.host, used),
                    address = v4?.host ?: "",
                    port = v4?.port ?: 0,
                    addressV6 = v6?.host ?: "",
                    portV6 = v6?.port ?: 0
                )
            )
        }
        return out
    }

    private fun uniqueName(server: VpnConfig, fallback: String, used: MutableSet<String>): String {
        val base = server.name.trim().ifEmpty { fallback }
        if (used.add(base)) return base
        var n = 2
        while (!used.add("$base-$n")) n++
        return "$base-$n"
    }

    /**
     * Address-family policy for `[selection]`, following the core's own table
     * (`endpoint.FamilyPolicyFromLegacy`): prefer-ipv6 off means v4_only, on
     * means prefer_v6 with fallback and v6_only without. prefer_v4 is not
     * reachable from the flag pair and so is never produced here.
     *
     * With no v6 address anywhere in the pool the answer is v4_only regardless
     * of the flags. Today the app only sends -prefer-ipv6/-fallback-v4 when the
     * active server has a v6 endpoint, so honouring a stray prefer-ipv6=true on
     * a v4-only pool would be a new behaviour - and with fallback-v4 off it
     * would leave the client with no candidate to dial at all.
     */
    fun familyFor(entries: List<Entry>, active: VpnConfig): String {
        if (entries.none { it.addressV6.isNotEmpty() }) return "v4_only"
        if (!active.preferIpv6) return "v4_only"
        return if (active.fallbackV4) "prefer_v6" else "v6_only"
    }

    /**
     * Render the config text.
     *
     * The secret is deliberately absent. The core accepts a per-entry `secret`,
     * but the pool is single-secret by construction and the value already
     * reaches the core as `-secret`, which on Android is an in-process argument
     * array rather than a visible argv. Writing it here would put a second copy
     * on disk and buy nothing; leaving it out makes reconcileSecrets a no-op
     * and the client keeps using the flag's value.
     *
     * `health_check` is left off for the reason the core's example config gives:
     * polling N servers on a timer is a periodic fan-out pattern with no cover
     * traffic behind it, which is the shape a censor looks for. The client
     * learns a server is down by dialling it.
     */
    fun render(entries: List<Entry>, active: VpnConfig): String {
        require(entries.isNotEmpty()) { "render: empty server pool" }

        val sb = StringBuilder()
        sb.append("# Generated by TiredVPN on every connect. Edits are lost.\n")
        sb.append("# Endpoint pool for the core's automatic failover.\n")

        for (e in entries) {
            sb.append("\n[[servers]]\n")
            sb.append("name = ").append(quote(e.name)).append('\n')
            if (e.address.isNotEmpty()) {
                sb.append("address = ").append(quote(e.address)).append('\n')
                sb.append("port = ").append(e.port).append('\n')
            }
            if (e.addressV6.isNotEmpty()) {
                sb.append("address_v6 = ").append(quote(e.addressV6)).append('\n')
                sb.append("port_v6 = ").append(e.portV6).append('\n')
            }
        }

        sb.append("\n[selection]\n")
        sb.append("policy = ").append(quote(POLICY)).append('\n')
        sb.append("family = ").append(quote(familyFor(entries, active))).append('\n')
        sb.append("failure_threshold = ").append(FAILURE_THRESHOLD).append('\n')
        sb.append("cooldown = ").append(quote(COOLDOWN)).append('\n')
        sb.append("max_cooldown = ").append(quote(MAX_COOLDOWN)).append('\n')
        sb.append("min_dwell = ").append(quote(MIN_DWELL)).append('\n')
        return sb.toString()
    }

    /** TOML basic string. Server names are user input and reach the file verbatim. */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\b' -> sb.append("\\b")
                c == '\t' -> sb.append("\\t")
                c == '\n' -> sb.append("\\n")
                c == '\u000C' -> sb.append("\\f")
                c == '\r' -> sb.append("\\r")
                c < ' ' || c == '\u007F' -> sb.append(String.format("\\u%04X", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Write the config into [dir] (the app's private files directory) and hand
     * back the file. Overwrites whatever was there: the pool is rebuilt from
     * the repository on every connect.
     *
     * The file names every endpoint the user dials, so it is clamped to
     * owner-only even though the directory is already app-private, and it never
     * goes to the cache directory - the system may hand that to another
     * process's cleaner.
     */
    fun write(dir: File, contents: String): File {
        val file = File(dir, FILE_NAME)
        file.writeText(contents)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
        return file
    }

    /** Remove the generated file. Safe to call when it was never written. */
    fun delete(dir: File) {
        File(dir, FILE_NAME).delete()
    }
}
