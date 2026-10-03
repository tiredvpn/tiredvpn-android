package com.tiredvpn.android.vpn

/**
 * What the in-app kill switch changes, as decisions the service asks for.
 *
 * The VPN interface is the kill switch: while it is up, apps' traffic goes into
 * the tunnel, and with no core behind it that traffic goes nowhere instead of
 * around the VPN. Since the interface is kept across failed attempts, network
 * switches and reconnects anyway, what is left for the switch is the paths
 * that still let go of it while the process is alive and the user has not
 * pressed Disconnect.
 *
 * It does not cover the process dying, another VPN taking the slot, or the
 * time before the first connect has built an interface. Only the system's
 * "Block connections without VPN" covers those; see [SystemVpnMode].
 *
 * The switch used to mean `Builder.setBlocking(true)`, which sets the blocking
 * mode of the descriptor and blocks no traffic at all.
 */
internal object KillSwitch {

    /** SharedPreferences key in `tiredvpn_settings`; the same key the old toggle used. */
    const val PREF = "kill_switch"

    /**
     * Whether the retry loop gives up after [attempt] and tears the tunnel
     * down. Holding, it never does: the interface stays and the loop keeps
     * going at its capped delay until the user disconnects.
     */
    fun givesUp(attempt: Int, holding: Boolean): Boolean =
        !holding && ReconnectBackoff.exhausted(attempt)

    /**
     * The state to publish after a failed attempt.
     *
     * Error reads as "Disconnected" in the UI and its button connects, which
     * on the way tears the interface down. While the switch holds an
     * interface the tunnel is not down, it is reconnecting, and the button has
     * to be the one that ends it.
     */
    fun stateAfterFailedAttempt(message: String, holding: Boolean, interfaceHeld: Boolean): VpnState =
        if (holding && interfaceHeld) VpnState.Connecting else VpnState.Error(message)
}
