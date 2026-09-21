package com.tiredvpn.android.vpn

/**
 * Names the transport change behind a `network_changed`, in the core's own
 * vocabulary.
 *
 * The core has taken this since the control socket was written —
 * `ControlCommand.Reason`, documented in `internal/tun/control.go` as
 * "wifi_to_lte", "lte_to_wifi" or "cell_handoff" — and the command has always
 * gone out without it, so every reconnect in a phone log reads the same.
 * The transition itself was already computed: the active-network monitor
 * tracks the previous and current transport on a two-second tick and throws
 * the pair away after comparing them.
 *
 * It changes no behaviour on either side. `handleReconnect` logs it and puts
 * it in the `data` of the `reconnecting` event; that is the whole point — the
 * question "was this a handoff or a Wi-Fi drop" stops needing a guess.
 */
internal object NetworkTransition {

    /** Transport names as the active network monitor spells them. */
    const val WIFI = "wifi"
    const val MOBILE = "mobile"

    /**
     * @param from previous transport, as the monitor spells it
     * @param to   current transport
     * @return the core's spelling, or "" when the pair is not one of the three
     *         it knows. An empty reason is omitted from the command rather than
     *         sent as a blank string, so the core sees exactly what it saw
     *         before.
     */
    fun reason(from: String, to: String): String = when {
        from == WIFI && to == MOBILE -> "wifi_to_lte"
        from == MOBILE && to == WIFI -> "lte_to_wifi"
        // Same transport, different network: a cell handing off to the next
        // one. Wi-Fi to Wi-Fi is a roam between access points, which the core
        // has no name for, so it stays unnamed rather than borrowing one.
        from == MOBILE && to == MOBILE -> "cell_handoff"
        else -> ""
    }
}
