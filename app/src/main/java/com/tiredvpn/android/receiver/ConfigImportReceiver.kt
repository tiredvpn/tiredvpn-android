package com.tiredvpn.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.tiredvpn.android.importer.ConfigCodec
import com.tiredvpn.android.importer.ConfigImporter
import java.io.File

/**
 * Silent config import for automation that is signed with the app's own key.
 *
 * ## This is NOT the adb path
 *
 * The receiver is declared with `android:permission=
 * "com.tiredvpn.android.permission.VPN_CONTROL"`, a signature-level permission.
 * `adb shell` runs as uid 2000, holds no signature permissions, and cannot be
 * granted one with `pm grant` - so the platform drops the broadcast before it is
 * delivered while `am broadcast` cheerfully prints `Broadcast completed:
 * result=0`. The adb examples this file used to carry never worked.
 *
 * For adb, use [com.tiredvpn.android.ui.ImportActivity], which is reachable with
 * `am start` and asks the user to confirm.
 *
 * ## What this receiver still does
 *
 * A companion app signed with the same key (a provisioning tool, a test harness)
 * can import without any UI:
 *
 *     Intent("com.tiredvpn.IMPORT_CONFIG")
 *         .setPackage("com.tiredvpn.android")
 *         .putExtra("payload", "tired://1.2.3.4:995?secret=xxx")
 *
 * ## Payload
 *
 * `payload` (or the legacy `json` / `url`) takes anything [ConfigCodec]
 * understands: one tired:// link, several links separated by newlines, a server
 * JSON object, a JSON array of servers, a `{"servers":[...]}` bundle, or base64
 * of any of those. `file` names a file to read instead.
 *
 * Field names follow one vocabulary across links, JSON and this receiver; see
 * [ConfigCodec] for the accepted spellings.
 */
class ConfigImportReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_IMPORT_CONFIG = "com.tiredvpn.IMPORT_CONFIG"
        private const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_JSON = "json"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE = "file"
        private const val TAG = "ConfigImportReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_IMPORT_CONFIG) return

        val payload = extractPayload(intent)
        if (payload.isNullOrBlank()) {
            showToast(context, "Import error: no config provided (payload or file extra)")
            return
        }

        val parsed = ConfigCodec.parse(payload)
        val plan = ConfigImporter.plan(context, parsed)
        Log.i(
            TAG,
            "import: format=${parsed.format} add=${plan.toAdd.size} " +
                "update=${plan.toUpdate.size} skip=${plan.skipped.size}"
        )

        if (!plan.hasWork) {
            val why = plan.skipped.firstOrNull()?.reason ?: "no server config recognised"
            showToast(context, "Import error: $why")
            return
        }

        val result = ConfigImporter.apply(context, plan)
        showToast(
            context,
            "Imported: ${result.added} added, ${result.updated} updated, ${result.skipped} skipped"
        )
    }

    private fun extractPayload(intent: Intent): String? {
        for (key in listOf(EXTRA_PAYLOAD, EXTRA_JSON, EXTRA_URL)) {
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val path = intent.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val file = File(path)
            if (!file.isFile || !file.canRead()) {
                Log.w(TAG, "config file is not readable")
                null
            } else {
                file.readText()
            }
        } catch (e: Exception) {
            // The content can be anything; keep it and the message out of the log.
            Log.w(TAG, "could not read config file: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun showToast(context: Context, message: String) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
