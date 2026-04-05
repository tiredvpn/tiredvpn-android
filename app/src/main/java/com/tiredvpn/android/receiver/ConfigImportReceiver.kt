package com.tiredvpn.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.VpnConfig
import org.json.JSONObject
import java.io.File

/**
 * BroadcastReceiver for importing VPN configuration via ADB.
 *
 * Usage examples:
 *
 * 1. Import from JSON file:
 *    adb push config.json /sdcard/Download/
 *    adb shell am broadcast -a com.tiredvpn.IMPORT_CONFIG \
 *      -n com.tiredvpn.android/.receiver.ConfigImportReceiver \
 *      --es file "/sdcard/Download/config.json"
 *
 * 2. Import inline JSON:
 *    adb shell am broadcast -a com.tiredvpn.IMPORT_CONFIG \
 *      -n com.tiredvpn.android/.receiver.ConfigImportReceiver \
 *      --es json '{"server":"1.2.3.4","port":443,"secret":"xxx"}'
 *
 * Config JSON format:
 * {
 *   "name": "My Server",
 *   "server": "vpn.example.com",
 *   "port": 443,
 *   "secret": "my-secret",
 *   "strategy": "auto",
 *   "quic": true,
 *   "quic_port": 443,
 *   "cover_host": "api.googleapis.com",
 *   "rtt_masking": false,
 *   "rtt_profile": "moscow-yandex",
 *   "fallback": true,
 *   "debug": false,
 *   "split_tunneling": {
 *     "mode": "exclude",
 *     "apps": ["com.google.android.youtube", "com.netflix.mediaclient"]
 *   }
 * }
 */
class ConfigImportReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_IMPORT_CONFIG = "com.tiredvpn.IMPORT_CONFIG"
        private const val EXTRA_JSON = "json"
        private const val EXTRA_FILE = "file"
        private const val PREFS_SETTINGS = "tiredvpn_settings"
        private const val TAG = "ConfigImportReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")
        if (intent.action != ACTION_IMPORT_CONFIG) return

        try {
            val jsonString = when {
                intent.hasExtra(EXTRA_JSON) -> {
                    Log.d(TAG, "Reading from EXTRA_JSON")
                    intent.getStringExtra(EXTRA_JSON)
                }
                intent.hasExtra(EXTRA_FILE) -> {
                    Log.d(TAG, "Reading from file: ${intent.getStringExtra(EXTRA_FILE)}")
                    readConfigFile(intent.getStringExtra(EXTRA_FILE))
                }
                else -> {
                    showToast(context, "Error: No config provided. Use --es json or --es file")
                    return
                }
            }

            if (jsonString.isNullOrBlank()) {
                showToast(context, "Error: Empty config")
                return
            }

            Log.d(TAG, "JSON received, length=${jsonString.length}")
            val json = JSONObject(jsonString)
            importConfig(context, json)
            showToast(context, "Config imported successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "Import error", e)
            showToast(context, "Import error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun readConfigFile(filePath: String?): String? {
        if (filePath.isNullOrBlank()) return null
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            throw IllegalArgumentException("Cannot read file: $filePath")
        }
        return file.readText()
    }

    private fun importConfig(context: Context, json: JSONObject) {
        // Parse server config
        val config = VpnConfig(
            name = json.optString("name", "Imported Server"),
            serverAddress = json.optString("server", json.optString("serverAddress", "")),
            serverPort = json.optInt("port", json.optInt("serverPort", 443)),
            secret = json.optString("secret", ""),
            strategy = json.optString("strategy", "auto"),
            enableQuic = json.optBoolean("quic", json.optBoolean("enableQuic", true)),
            quicPort = json.optInt("quic_port", json.optInt("quicPort", 443)),
            coverHost = json.optString("cover_host", json.optString("coverHost", "api.googleapis.com")),
            rttMasking = json.optBoolean("rtt_masking", json.optBoolean("rttMasking", false)),
            rttProfile = json.optString("rtt_profile", json.optString("rttProfile", "moscow-yandex")),
            fallbackEnabled = json.optBoolean("fallback", json.optBoolean("fallbackEnabled", true)),
            debugLogging = json.optBoolean("debug", json.optBoolean("debugLogging", false))
        )

        if (!config.isValid) {
            throw IllegalArgumentException("Invalid config: server, port and secret are required")
        }

        // Save server
        ServerRepository.saveServer(context, config)
        ServerRepository.setActiveServerId(context, config.id)

        // Import split tunneling if present
        Log.d(TAG, "Checking for split_tunneling: has=${json.has("split_tunneling")}")
        if (json.has("split_tunneling")) {
            val splitObj = json.getJSONObject("split_tunneling")
            Log.d(TAG, "split_tunneling object: $splitObj")
            importSplitTunneling(context, splitObj)
        } else {
            Log.d(TAG, "No split_tunneling in JSON. Keys: ${json.keys().asSequence().toList()}")
        }
    }

    private fun importSplitTunneling(context: Context, splitJson: JSONObject) {
        val mode = splitJson.optString("mode", "exclude")
        val appsArray = splitJson.optJSONArray("apps")

        Log.d(TAG, "importSplitTunneling: mode=$mode, appsArray=$appsArray")

        val apps = mutableSetOf<String>()
        if (appsArray != null) {
            for (i in 0 until appsArray.length()) {
                apps.add(appsArray.getString(i))
            }
        }

        Log.d(TAG, "Saving split tunneling: mode=$mode, apps=$apps")
        context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString("split_tunneling_mode", mode)
            .putStringSet("split_tunneling_apps", apps)
            .apply()
        Log.d(TAG, "Split tunneling saved successfully")
    }

    private fun showToast(context: Context, message: String) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
