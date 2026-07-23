package com.tiredvpn.android.vpn

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-profile split tunneling settings.
 *
 * Settings are keyed by the profile (VpnConfig) id so each server can have its own
 * mode + app list. Legacy global keys ("split_tunneling_mode"/"split_tunneling_apps")
 * are used as a fallback default until a profile gets its own value, so existing users
 * keep their configuration after the upgrade.
 */
object SplitTunnelSettings {

    const val PREFS_NAME = "tiredvpn_settings"

    // Legacy global keys (pre per-profile). Still read as a default fallback.
    private const val LEGACY_MODE = "split_tunneling_mode"
    private const val LEGACY_APPS = "split_tunneling_apps"

    const val DEFAULT_MODE = "exclude"

    private fun modeKey(profileId: String?) =
        if (profileId.isNullOrEmpty()) LEGACY_MODE else "${LEGACY_MODE}_$profileId"

    private fun appsKey(profileId: String?) =
        if (profileId.isNullOrEmpty()) LEGACY_APPS else "${LEGACY_APPS}_$profileId"

    fun getMode(prefs: SharedPreferences, profileId: String?): String {
        val key = modeKey(profileId)
        if (prefs.contains(key)) {
            return prefs.getString(key, DEFAULT_MODE) ?: DEFAULT_MODE
        }
        // Fallback to legacy global value for a smooth migration.
        return prefs.getString(LEGACY_MODE, DEFAULT_MODE) ?: DEFAULT_MODE
    }

    fun getApps(prefs: SharedPreferences, profileId: String?): Set<String> {
        val key = appsKey(profileId)
        if (prefs.contains(key)) {
            return prefs.getStringSet(key, emptySet()) ?: emptySet()
        }
        return prefs.getStringSet(LEGACY_APPS, emptySet()) ?: emptySet()
    }

    fun save(prefs: SharedPreferences, profileId: String?, mode: String, apps: Set<String>) {
        prefs.edit()
            .putString(modeKey(profileId), mode)
            .putStringSet(appsKey(profileId), apps)
            .apply()
    }

    // Convenience overloads that resolve prefs from a Context.

    fun getMode(context: Context, profileId: String?): String =
        getMode(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), profileId)

    fun getApps(context: Context, profileId: String?): Set<String> =
        getApps(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), profileId)

    fun save(context: Context, profileId: String?, mode: String, apps: Set<String>) =
        save(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), profileId, mode, apps)
}
