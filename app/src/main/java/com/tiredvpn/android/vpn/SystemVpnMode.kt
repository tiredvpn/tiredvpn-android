package com.tiredvpn.android.vpn

import android.content.SharedPreferences

/**
 * The system's always-on and "Block connections without VPN" settings for this
 * app, as last seen by the service.
 *
 * `VpnService.isAlwaysOn()` and `isLockdownEnabled()` (API 29) are methods of
 * the service instance, so a screen cannot ask for them; the service writes
 * what it read on every start, and the screen shows that with its age. Older
 * Android does not tell an app either value.
 */
internal object SystemVpnMode {

    private const val KEY_ALWAYS_ON = "system_always_on"
    private const val KEY_LOCKDOWN = "system_lockdown"
    private const val KEY_READ_AT = "system_vpn_mode_read_at"

    data class Reading(val alwaysOn: Boolean, val lockdown: Boolean, val readAtMs: Long)

    fun record(prefs: SharedPreferences, alwaysOn: Boolean, lockdown: Boolean, nowMs: Long) {
        prefs.edit()
            .putBoolean(KEY_ALWAYS_ON, alwaysOn)
            .putBoolean(KEY_LOCKDOWN, lockdown)
            .putLong(KEY_READ_AT, nowMs)
            .apply()
    }

    /** The last reading, or null when the service has not run since install. */
    fun last(prefs: SharedPreferences): Reading? {
        val at = prefs.getLong(KEY_READ_AT, 0L)
        if (at == 0L) return null
        return Reading(prefs.getBoolean(KEY_ALWAYS_ON, false), prefs.getBoolean(KEY_LOCKDOWN, false), at)
    }
}
