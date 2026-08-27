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
 *  - A secret per entry. Core 1.8.0 made the key a property of the dial: it
 *    travels on the context and comes from whichever endpoint is being reached
 *    (`internal/strategy/secret.go`), so `[[servers]]` entries may disagree
 *    and `reconcileSecrets` no longer rejects them. Before that the pool had to
 *    be one secret wide, which is why [selectPool] used to filter.
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
        val portV6: Int = 0,
        /**
         * The key this endpoint is dialled with. Blank means the entry falls
         * back to the process default (`-secret`), which after [selectPool] can
         * only happen to the active server.
         */
        val secret: String = ""
    )

    /** A host with its port, after splitting an "address:port" string. */
    data class HostPort(val host: String, val port: Int)

    /**
     * The servers the core is allowed to switch between: [active] first (the
     * priority policy dials in list order), then every other configured server
     * in repository order. Secrets no longer group anything - each entry
     * carries its own key into the file, so a switch switches the key with it.
     *
     * The one thing still filtered out is a server with a blank secret, and for
     * a different reason than before: it has no key to write, so it would land
     * in the file as an entry that silently borrows the process default and
     * then fails to authenticate. It is a half-filled form, not a destination.
     * The same test applied to [active] short-circuits the whole pool, which
     * keeps the file self-sufficient - every entry it contains names its key.
     */
    fun selectPool(servers: List<VpnConfig>, active: VpnConfig): List<VpnConfig> {
        if (active.secret.isBlank()) return listOf(active)
        val rest = servers.filter { it.id != active.id && it.secret.isNotBlank() }
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
                    portV6 = v6?.port ?: 0,
                    secret = server.secret
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
     * The endpoint half of the core's argument list: either the pool file or
     * the single server it degenerates to, never both.
     *
     * This is the one rule in the whole feature that fails silently when it is
     * broken. `-server` and `-server-v6` each set `sawServerFlag` in the JNI arg
     * parser, which then calls `collapseServers(...)` and reduces a `-config`
     * pool to one entry - no error, no warning, and a client that looks
     * perfectly healthy right up until the first exit dies with nowhere to go.
     * So the decision lives here, as one function two call sites paste in
     * whole, rather than as an `if` inside a service no unit test can start.
     *
     * The family flags travel with `-server-v6` because they are meaningless
     * without it: with a pool, both the addresses and the family policy come
     * from `[selection]` in the file.
     */
    fun endpointArgs(
        poolConfigPath: String?,
        serverEndpoint: String,
        serverAddressV6: String,
        preferIpv6: Boolean,
        fallbackV4: Boolean
    ): List<String> {
        val configPath = poolConfigPath?.trim().orEmpty()
        if (configPath.isNotEmpty()) {
            return listOf("-config", configPath)
        }

        val endpoint = serverEndpoint.trim()
        // A blank address would become -server "", which the JNI parser accepts
        // happily: it takes the empty value and sets the collapse flag anyway.
        // Leaving the flag out gets the core's own "-server is required" instead.
        if (endpoint.isEmpty()) return emptyList()

        val args = mutableListOf("-server", endpoint)
        val v6 = serverAddressV6.trim()
        if (v6.isNotEmpty()) {
            args.addAll(listOf("-server-v6", v6))
            args.addAll(listOf("-prefer-ipv6", preferIpv6.toString()))
            args.addAll(listOf("-fallback-v4", fallbackV4.toString()))
        }
        return args
    }

    /**
     * Render the config text.
     *
     * Every entry carries its `secret`. There is no other channel: `-secret` is
     * a single process-wide value, so a pool whose members disagree can only
     * state its keys here. That makes this file the app's whole keyring rather
     * than a list of addresses - see [write] for what follows from that.
     *
     * `-secret` stays on the command line anyway, set to the active server's
     * key. The core treats it as the default for entries that name none
     * (`reconcileSecrets`, `strategy.Manager.defaultSecret`), so with every
     * entry naming one it is never consulted for a dial; what it does buy is
     * the degenerate path, where a pool of one falls back to `-server` and the
     * file is not written at all. Dropping the flag would leave that path with
     * no key and the core warning "No secret provided - using default".
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
            // Through the same escaper as the name: a secret is user input too,
            // and a quote in one would end the string and turn the rest of the
            // key into TOML the core then rejects.
            if (e.secret.isNotEmpty()) {
                sb.append("secret = ").append(quote(e.secret)).append('\n')
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
     * The file names every endpoint the user dials AND the key for each, so it
     * is clamped to owner-only even though the directory is already app-private,
     * and it never goes to the cache directory - the system may hand that to
     * another process's cleaner. It is deleted when the core stops
     * (TiredVpnService cleanup and forceResetCore both call [delete]), so the
     * keyring exists on disk only while the tunnel it feeds does.
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
