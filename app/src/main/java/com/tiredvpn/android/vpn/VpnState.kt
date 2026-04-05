package com.tiredvpn.android.vpn

sealed class VpnState {
    data object Disconnected : VpnState()
    data object Connecting : VpnState()
    data class Connected(
        val strategy: String,
        val latencyMs: Long = 0,
        val attempts: Int = 1,
        val mode: String = "tun",
        val proxyAddress: String? = null,
        // Port hopping info
        val currentPort: Int? = null,
        val portHoppingEnabled: Boolean = false
    ) : VpnState()
    data class Error(val message: String) : VpnState()
}
