package com.tiredvpn.android.vpn

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

object ServerRepository {
    private const val PREFS_NAME = "tiredvpn_servers"
    private const val PREFS_NAME_ENCRYPTED = "tiredvpn_servers_enc"
    private const val KEY_SERVERS = "servers"
    private const val KEY_ACTIVE_SERVER_ID = "active_server_id"

    // Legacy prefs for migration
    private const val LEGACY_PREFS_NAME = "tiredvpn_config"

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME_ENCRYPTED,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            getEncryptedPrefs(context)
        } catch (e: Exception) {
            // Fallback to plaintext if encrypted prefs unavailable (e.g. keystore issue)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getServers(context: Context): List<VpnConfig> {
        val prefs = getPrefs(context)

        // Check for migration only if KEY_SERVERS doesn't exist in encrypted prefs
        if (!prefs.contains(KEY_SERVERS)) {
            migratePlaintextToEncrypted(context)
            migrateLegacyConfig(context)
        }

        return loadServersRaw(context)
    }

    private fun loadServersRaw(context: Context): MutableList<VpnConfig> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
        val list = mutableListOf<VpnConfig>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                list.add(VpnConfig.fromJson(jsonArray.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getServer(context: Context, id: String): VpnConfig? {
        return getServers(context).find { it.id == id }
    }

    fun saveServer(context: Context, config: VpnConfig) {
        // Use raw load to avoid recursion loop during migration
        val servers = loadServersRaw(context)
        val index = servers.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            servers[index] = config
        } else {
            servers.add(config)
        }
        saveServers(context, servers)

        // If this is the only server, or no active server is set, make it active
        if (servers.size == 1 || getActiveServer(context) == null) {
            setActiveServerId(context, config.id)
        }
    }

    fun deleteServer(context: Context, id: String) {
        val servers = loadServersRaw(context)
        val wasActive = getActiveServer(context)?.id == id

        servers.removeAll { it.id == id }
        saveServers(context, servers)

        if (wasActive) {
            // Select another server if available
            val nextServer = servers.firstOrNull()
            if (nextServer != null) {
                setActiveServerId(context, nextServer.id)
            } else {
                // No servers left
                clearActiveServerId(context)
            }
        }
    }

    private fun saveServers(context: Context, servers: List<VpnConfig>) {
        val jsonArray = JSONArray()
        servers.forEach { jsonArray.put(it.toJson()) }
        getPrefs(context)
            .edit()
            .putString(KEY_SERVERS, jsonArray.toString())
            .apply()
    }

    fun getActiveServer(context: Context): VpnConfig? {
        // Here we can use getServers() because getActiveServer isn't called inside migration loop
        val servers = getServers(context)
        if (servers.isEmpty()) return null

        val activeId = getPrefs(context).getString(KEY_ACTIVE_SERVER_ID, null)

        return servers.find { it.id == activeId } ?: servers.firstOrNull()?.also {
            // If active ID not found but servers exist, default to first
            setActiveServerId(context, it.id)
        }
    }

    fun setActiveServerId(context: Context, id: String) {
        getPrefs(context)
            .edit()
            .putString(KEY_ACTIVE_SERVER_ID, id)
            .apply()
    }

    private fun clearActiveServerId(context: Context) {
        getPrefs(context)
            .edit()
            .remove(KEY_ACTIVE_SERVER_ID)
            .apply()
    }

    // Migrate from old plaintext tiredvpn_servers to encrypted tiredvpn_servers_enc
    private fun migratePlaintextToEncrypted(context: Context) {
        val plainPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!plainPrefs.contains(KEY_SERVERS)) return

        try {
            val encPrefs = getEncryptedPrefs(context)
            encPrefs.edit()
                .putString(KEY_SERVERS, plainPrefs.getString(KEY_SERVERS, "[]"))
                .putString(KEY_ACTIVE_SERVER_ID, plainPrefs.getString(KEY_ACTIVE_SERVER_ID, null))
                .apply()
            plainPrefs.edit().clear().apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun migrateLegacyConfig(context: Context) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (legacyPrefs.contains("server_address")) {
            val serverAddress = legacyPrefs.getString("server_address", "") ?: ""
            if (serverAddress.isNotBlank()) {
                val config = VpnConfig(
                    name = "Default Server",
                    serverAddress = serverAddress,
                    serverPort = legacyPrefs.getInt("server_port", 993),
                    secret = legacyPrefs.getString("secret", "") ?: "",
                    strategy = legacyPrefs.getString("strategy", "auto") ?: "auto",
                    enableQuic = legacyPrefs.getBoolean("enable_quic", true),
                    quicPort = legacyPrefs.getInt("quic_port", 443),
                    coverHost = legacyPrefs.getString("cover_host", "api.googleapis.com") ?: "api.googleapis.com",
                    rttMasking = legacyPrefs.getBoolean("rtt_masking", false),
                    rttProfile = legacyPrefs.getString("rtt_profile", "moscow-yandex") ?: "moscow-yandex",
                    fallbackEnabled = legacyPrefs.getBoolean("fallback_enabled", true),
                    debugLogging = legacyPrefs.getBoolean("debug_logging", false)
                )
                // Use saveServer, which now uses loadServersRaw, preventing recursion
                saveServer(context, config)
            }
            // Mark migration as done by ensuring KEY_SERVERS exists (done by saveServer)
            // Also clear legacy to be safe
            legacyPrefs.edit().clear().apply()
        }
    }
}
