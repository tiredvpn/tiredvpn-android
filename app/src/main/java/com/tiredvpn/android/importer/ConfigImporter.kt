package com.tiredvpn.android.importer

import android.content.Context
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.SplitTunnelSettings
import com.tiredvpn.android.vpn.VpnConfig

/**
 * Decides what a parsed payload would do to the stored server list, then does it.
 *
 * Planning is separated from applying so the preview the user confirms and the
 * write that follows are produced by the same code. A preview computed by one
 * routine and a save performed by another is how "it said 4, it stored 1" bugs
 * happen.
 */
object ConfigImporter {

    enum class Action {
        /** No stored server matches; this one gets appended. */
        ADD,

        /** A stored server matches; its credentials and options are replaced. */
        UPDATE,

        /** An earlier entry in the same payload already claimed this endpoint. */
        DUPLICATE,
    }

    data class PlannedEntry(
        val config: VpnConfig,
        val action: Action,
        val splitTunnel: ConfigCodec.SplitTunnelSpec? = null,
    ) {
        /** Endpoint and name only - never the secret; this string reaches the UI. */
        val label: String get() = "${config.name} (${config.serverAddress}:${config.serverPort})"
    }

    data class Plan(
        val entries: List<PlannedEntry>,
        val skipped: List<ConfigCodec.Skipped>,
    ) {
        val toAdd: List<PlannedEntry> get() = entries.filter { it.action == Action.ADD }
        val toUpdate: List<PlannedEntry> get() = entries.filter { it.action == Action.UPDATE }
        val duplicates: List<PlannedEntry> get() = entries.filter { it.action == Action.DUPLICATE }

        /** Entries that will actually be written. */
        val writable: List<PlannedEntry>
            get() = entries.filter { it.action != Action.DUPLICATE }

        val hasWork: Boolean get() = writable.isNotEmpty()
    }

    /**
     * [failed] counts entries the store refused. It is separate from [skipped],
     * which is about the payload - a malformed link, a duplicate - and is
     * therefore the user's problem to fix. A refusal is the device's, and used
     * to be invisible: `apply` called saveServer, dropped the false it returned
     * and reported the entry as written anyway.
     */
    data class Result(
        val added: Int,
        val updated: Int,
        val skipped: Int,
        val failed: Int = 0,
    )

    const val REASON_DUPLICATE = "already listed earlier in this import"

    /**
     * Two payload entries describe the same server when they dial the same
     * place. Not the name (users rename), not the id (a tired:// link carries no
     * id, so every parse of the same link mints a fresh one - keying on it is
     * exactly what made re-importing a link create a second entry).
     *
     * Since core 1.8.0 each pool node has its own secret, so a changed secret on
     * a known endpoint is a rotated credential, not a different server: update.
     */
    fun dedupKey(config: VpnConfig): String {
        val v6 = v6Key(config)
        return if (v6.isEmpty()) v4Key(config) else "${v4Key(config)}|$v6"
    }

    /** The v4 or hostname endpoint, normalised. */
    private fun v4Key(config: VpnConfig): String {
        // An IPv6 literal reaches serverAddress with brackets when Uri parsed the
        // link and without them when the manual authority fallback did. Same host.
        val host = config.serverAddress.trim().lowercase().trim('[', ']')
        return "$host:${config.serverPort}"
    }

    /** The v6 endpoint, normalised; "" when the entry does not name one. */
    private fun v6Key(config: VpnConfig): String =
        config.serverAddressV6.trim().lowercase().filterNot { it == '[' || it == ']' }

    /**
     * Do two entries describe the same server?
     *
     * The v4 endpoint has to match. The v6 endpoint is compared only when BOTH
     * sides name one, because "said nothing about v6" is not "has no v6": a bare
     * tired:// link carries no serverV6 parameter at all, and treating the short
     * form of a server as a different server would mint a second entry every
     * time someone re-imported it.
     *
     * When both sides do name a v6 endpoint and the two differ, they are two
     * ways into two different places and both are kept. Keying on the v4
     * endpoint alone used to collapse them: the second was reported as a
     * duplicate inside one payload, and overwrote the first on re-import.
     */
    fun sameServer(a: VpnConfig, b: VpnConfig): Boolean {
        if (v4Key(a) != v4Key(b)) return false
        val av6 = v6Key(a)
        val bv6 = v6Key(b)
        return av6.isEmpty() || bv6.isEmpty() || av6 == bv6
    }

    fun plan(existing: List<VpnConfig>, parsed: ConfigCodec.ParseResult): Plan {
        val byId = existing.associateBy { it.id }

        val entries = mutableListOf<PlannedEntry>()
        val skipped = parsed.skipped.toMutableList()
        // Two payload entries collide in two ways: they dial the same place, or
        // they resolve to the same stored row. Claiming endpoints alone let the
        // second kind through - both entries planned as UPDATE of one row, the
        // later write silently on top of the earlier, and "2 updated" reported.
        val claimedEndpoints = mutableListOf<VpnConfig>()
        val claimedIds = mutableSetOf<String>()

        for (server in parsed.servers) {
            val incoming = server.config

            // An id match comes first: a backup file round-trips by id, which
            // survives the user moving a server to a different address.
            val match = byId[incoming.id] ?: existing.firstOrNull { sameServer(it, incoming) }
            val targetId = match?.id ?: incoming.id

            if (claimedEndpoints.any { sameServer(it, incoming) } || targetId in claimedIds) {
                entries += PlannedEntry(incoming, Action.DUPLICATE, server.splitTunnel)
                skipped += ConfigCodec.Skipped("${incoming.serverAddress}:${incoming.serverPort}", REASON_DUPLICATE)
                continue
            }
            claimedEndpoints += incoming
            claimedIds += targetId

            if (match == null) {
                entries += PlannedEntry(incoming, Action.ADD, server.splitTunnel)
            } else {
                entries += PlannedEntry(
                    merge(match, incoming, server.namedBySender, server.v6BySender),
                    Action.UPDATE,
                    server.splitTunnel,
                )
            }
        }
        return Plan(entries, skipped)
    }

    /**
     * Incoming values win, with two exceptions that are properties of this device
     * rather than of the server:
     *  - the stored id, so split-tunnel rules and the active-server pointer keep
     *    pointing at the same profile;
     *  - the measured latency, which the sender cannot know.
     *
     * A name the sender did not choose (a bare link names the server after its
     * host, a nameless JSON object gets "Server") must not overwrite a name the
     * user did choose. That question is answered by the codec, which knows
     * whether a name was in the payload - guessing it back from the string here
     * cost the name of everyone who called their server "Server".
     *
     * The IPv6 endpoint is the same trap one step further along, because the
     * value that means "the sender said nothing" is "" and so is the value that
     * means "this server has no IPv6 endpoint". A bare tired:// link carries no
     * serverV6 at all, so re-importing one over a server whose v6 endpoint the
     * user had filled in erased it, and `sameServer` deliberately treats the
     * short form as the same server - which is what routed it through here
     * rather than adding a second entry. Presence decides, so a payload that
     * does name the field, even empty, still removes the endpoint.
     */
    private fun merge(
        existing: VpnConfig,
        incoming: VpnConfig,
        namedBySender: Boolean,
        v6BySender: Boolean,
    ): VpnConfig =
        incoming.copy(
            id = existing.id,
            lastLatencyMs = existing.lastLatencyMs,
            name = if (namedBySender) incoming.name else existing.name,
            serverAddressV6 = if (v6BySender) incoming.serverAddressV6 else existing.serverAddressV6,
        )

    /** Plan against what is currently stored. */
    fun plan(context: Context, parsed: ConfigCodec.ParseResult): Plan =
        plan(ServerRepository.getServers(context), parsed)

    /**
     * Write a plan. Returns the counts the user is shown; they are derived from
     * the same list that was written, not recounted from the payload.
     *
     * @param mayChangeActiveServer whether this import is allowed to repoint the
     *   VPN at something it just wrote. False for every payload that arrived
     *   from outside the app: one tired:// link and one tap on Import was enough
     *   to move the user onto the sender's node, and nothing on screen said so.
     *   A device that has no active server yet is the exception - something has
     *   to be selected there and there is nothing to displace.
     */
    fun apply(context: Context, plan: Plan, mayChangeActiveServer: Boolean): Result {
        val hadActiveServer = ServerRepository.getActiveServer(context) != null
        val writable = plan.writable

        // Counted from what the store accepted, not from what was planned -
        // the same rule the counts already followed for the payload. Split
        // tunnel rules are written only for an entry that landed: rules
        // attached to a server that is not there point at nothing.
        val written = mutableListOf<PlannedEntry>()
        val refused = mutableListOf<PlannedEntry>()
        for (entry in writable) {
            if (!ServerRepository.saveServer(context, entry.config)) {
                refused += entry
                continue
            }
            written += entry
            entry.splitTunnel?.let {
                SplitTunnelSettings.save(context, entry.config.id, it.mode, it.apps)
            }
        }

        // Selecting an active server is only obvious when there is one candidate.
        // Importing a pool of four must not silently move the user onto whichever
        // node happened to be last in the file.
        //
        // "One candidate" is still counted over the plan the user confirmed,
        // not over what survived: two entries of which one was refused is not
        // a payload with one server in it. It just may not be pointed at
        // something the store did not take.
        val single = writable.singleOrNull()?.takeIf { it in written }
        if (single != null &&
            (!hadActiveServer || (mayChangeActiveServer && single.action == Action.ADD))
        ) {
            ServerRepository.setActiveServerId(context, single.config.id)
        }

        return Result(
            added = written.count { it.action == Action.ADD },
            updated = written.count { it.action == Action.UPDATE },
            skipped = plan.skipped.size,
            failed = refused.size,
        )
    }

    /** Parse, plan and write in one step. For non-interactive callers only. */
    fun importDirect(context: Context, raw: String?, mayChangeActiveServer: Boolean): Result {
        val parsed = ConfigCodec.parse(raw)
        return apply(context, plan(context, parsed), mayChangeActiveServer)
    }
}
