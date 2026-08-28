package com.tiredvpn.android.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Every place raw import text is read from something.
 *
 * These are small enough to look obviously correct and were, for that reason,
 * written out five times: two clipboard loops, two file reads and a content-uri
 * read. None of them was reachable from a test, so a payload could be truncated
 * on the way IN and every parser test would stay green - the codec never sees
 * the difference between "the user copied one link" and "we read one item of a
 * four-item clip".
 *
 * Nothing here parses. It only produces the string that goes to [ConfigCodec].
 */
object ImportSource {

    private const val TAG = "ImportSource"

    /** A config file no human writes; the cap keeps a stray /dev path harmless. */
    const val MAX_FILE_BYTES = 1L * 1024 * 1024

    /**
     * Read the clipboard whole.
     *
     * A clip holds a list of items, not a string. Copying four links can land as
     * four items, and reading item 0 alone loses three servers while still
     * looking like a successful import of one.
     */
    fun fromClipboard(context: Context): String {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return fromClip(context, clipboard?.primaryClip)
    }

    fun fromClip(context: Context, clip: ClipData?): String {
        if (clip == null || clip.itemCount == 0) return ""
        return (0 until clip.itemCount)
            .joinToString("\n") { index -> clip.getItemAt(index).coerceToText(context).toString() }
    }

    /**
     * Read a config file named by a caller we do not trust.
     *
     * @param blockedRoot a directory the path must not be inside. Callers pass
     *   the app's own private storage, so an untrusted caller cannot aim the
     *   import at our stored configs and have them rendered on screen.
     */
    fun fromFile(path: String?, blockedRoot: String? = null): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val file = File(path).canonicalFile
            val blocked = blockedRoot?.let { File(it).canonicalPath }
            if (blocked != null && file.path.startsWith("$blocked${File.separator}")) {
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

    /** Read a file the user picked in the system file picker. */
    fun fromUri(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "could not read picked file: ${e.javaClass.simpleName}")
            null
        }
    }
}
