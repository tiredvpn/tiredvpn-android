package com.tiredvpn.android.util

import android.content.Context
import java.io.File

/**
 * Where a file has to live before it can be handed out as a `content://` URI.
 *
 * `res/xml/file_paths.xml` grants FileProvider two roots under the cache
 * directory and no more — `share/` for things the user exports, `updates/` for
 * the downloaded APK. A file outside them is not "shared without a grant", it
 * is an `IllegalArgumentException` out of `getUriForFile`, which is what
 * narrowing those roots did to the two call sites that were still writing into
 * the root of `cacheDir`: the config backup died outright, and the log share
 * fell through to its plain-text fallback while the dialog kept promising a
 * file.
 *
 * So the path and the grant come from one place. Anything passed to
 * `FileProvider.getUriForFile` for sharing is created through [file].
 */
object SharedFiles {

    /** Must match the `path` of the `shared` cache-path in `file_paths.xml`. */
    const val DIR_NAME = "share"

    /**
     * What earlier versions wrote into the root of the cache directory.
     *
     * `tiredvpn-backup.json` is the plaintext export of every server profile,
     * secrets included, and it was left there after each share. Moving the new
     * writes into [DIR_NAME] does not remove the old ones, so an install that
     * ever exported a backup keeps that file until the system decides to
     * reclaim the cache — which it may never do.
     *
     * Only these two names, by name. The cache directory belongs to several
     * things (the downloaded APK, HTTP caches) and sweeping it is not ours to
     * do.
     */
    private val LEGACY_NAMES = listOf("tiredvpn-backup.json", "tiredvpn_logs.txt")

    fun dir(context: Context): File = File(context.cacheDir, DIR_NAME).apply {
        mkdirs()
        removeLegacyCopies(context)
    }

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** @return how many stale copies were deleted; for the test to observe. */
    internal fun removeLegacyCopies(context: Context): Int {
        var removed = 0
        for (name in LEGACY_NAMES) {
            val stale = File(context.cacheDir, name)
            if (stale.isFile && stale.delete()) removed++
        }
        return removed
    }
}
