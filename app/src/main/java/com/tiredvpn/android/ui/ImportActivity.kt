package com.tiredvpn.android.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.tiredvpn.android.importer.ConfigCodec
import com.tiredvpn.android.importer.ImportPreview
import java.io.File

/**
 * Every import that originates outside the app lands here: a tired:// deep link
 * tapped in a browser or messenger, a link shared into the app, and adb.
 *
 * ## Why an activity and not the broadcast receiver
 *
 * [com.tiredvpn.android.receiver.ConfigImportReceiver] is guarded by the
 * signature-level permission `com.tiredvpn.android.permission.VPN_CONTROL`.
 * `adb shell` runs as uid 2000 and holds no such permission - a signature
 * permission cannot be granted with `pm grant` either - so the platform drops
 * the broadcast before delivery while `am broadcast` still prints
 * `result=0`. Every documented adb import command in this app has therefore
 * been silently doing nothing.
 *
 * Checking the caller inside the receiver instead does not fix it: a
 * `BroadcastReceiver` cannot learn who sent a broadcast (`Binder.getCallingUid`
 * inside `onReceive` returns our own uid), so there is nothing to check.
 *
 * Dropping the permission would fix adb and open the hole it exists to close:
 * any installed app could then replace the user's VPN credentials silently.
 *
 * ## What protects this activity instead
 *
 * The activity is exported, so `am start` reaches it - and so can any other app.
 * The protection is that reaching it is not the same as changing anything:
 * nothing is written until the user reads [ImportPreview] - which names every
 * server that would be added or replaced - and taps Import. An attacker gets to
 * put a dialog on screen; that is a nuisance, not a credential swap. This is the
 * same trust model the `tired://` deep link has always used.
 *
 * ## Usage
 *
 *   # one link, or several separated by newlines, or base64 of either
 *   adb shell am start -n com.tiredvpn.android/.ui.ImportActivity \
 *     -a com.tiredvpn.IMPORT_CONFIG --es payload 'tired://1.2.3.4:995?secret=xxx'
 *
 *   # a JSON file pushed to the device
 *   adb push pool.json /sdcard/Download/
 *   adb shell am start -n com.tiredvpn.android/.ui.ImportActivity \
 *     -a com.tiredvpn.IMPORT_CONFIG --es file /sdcard/Download/pool.json
 *
 *   # a plain deep link, no component name needed
 *   adb shell am start -a android.intent.action.VIEW -d 'tired://1.2.3.4:995?secret=xxx'
 */
class ImportActivity : AppCompatActivity() {

    companion object {
        const val ACTION_IMPORT_CONFIG = "com.tiredvpn.IMPORT_CONFIG"

        /** Any accepted format, inline. */
        const val EXTRA_PAYLOAD = "payload"

        /** Accepted as aliases of [EXTRA_PAYLOAD]; the codec sniffs the format anyway. */
        const val EXTRA_JSON = "json"
        const val EXTRA_URL = "url"

        /** Path to a file readable by this app, e.g. something pushed to /sdcard. */
        const val EXTRA_FILE = "file"

        private const val TAG = "ImportActivity"

        /** A config file no human writes; the cap keeps a stray /dev path harmless. */
        private const val MAX_FILE_BYTES = 1L * 1024 * 1024

        /**
         * Pull the payload out of an intent, whatever route it came in by.
         *
         * Kept static and free of UI so the extraction can be tested directly -
         * a codec that parses correctly is useless if the screen hands it the
         * wrong string.
         */
        fun payloadFrom(context: Context, intent: Intent?): String? {
            if (intent == null) return null

            intent.data?.takeIf { it.scheme.equals("tired", ignoreCase = true) }
                ?.let { return it.toString() }

            if (intent.action == Intent.ACTION_SEND) {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }

            for (key in listOf(EXTRA_PAYLOAD, EXTRA_JSON, EXTRA_URL)) {
                intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it }
            }

            intent.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() }
                ?.let { return readFile(context, it) }

            // A deep link that arrived with a non-tired scheme, or ACTION_VIEW on
            // a file: fall back to the raw data string rather than dropping it.
            return intent.dataString?.takeIf { it.isNotBlank() }
        }

        /**
         * Read a config file named by the caller.
         *
         * Paths inside our own private storage are refused: an untrusted caller
         * must not be able to aim this at the app's own prefs or backup cache and
         * have the contents rendered on screen.
         */
        private fun readFile(context: Context, path: String): String? {
            return try {
                val file = File(path).canonicalFile
                val ownData = context.applicationInfo.dataDir?.let { File(it).canonicalPath }
                if (ownData != null && file.path.startsWith("$ownData${File.separator}")) {
                    Log.w(TAG, "refusing to read a file inside our own data dir")
                    return null
                }
                if (!file.isFile || !file.canRead()) {
                    Log.w(TAG, "config file is not readable")
                    return null
                }
                if (file.length() > MAX_FILE_BYTES) {
                    Log.w(TAG, "config file is too large: ${file.length()} bytes")
                    return null
                }
                file.readText()
            } catch (e: Exception) {
                // Never echo the content or the message: this path can be aimed at
                // arbitrary files and the app has leaked secrets into logs before.
                Log.w(TAG, "could not read config file: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val payload = payloadFrom(this, intent)
        val parsed = ConfigCodec.parse(payload)
        Log.i(TAG, "import requested: format=${parsed.format} servers=${parsed.servers.size}")
        // Always external: nothing reaches this activity from inside the app.
        ImportPreview.show(this, parsed, fromExternalSource = true) { finish() }
    }
}
