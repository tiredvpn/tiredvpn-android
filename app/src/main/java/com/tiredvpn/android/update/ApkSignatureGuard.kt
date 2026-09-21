package com.tiredvpn.android.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Refuses to hand the system installer an APK signed by someone else.
 *
 * The system installer would reject a foreign signature anyway, so this is not
 * the only thing standing between the user and a hostile APK. It is the thing
 * that stops a hostile APK from ever reaching an install prompt — which is the
 * moment a user is most likely to tap through. It also fails closed when the
 * APK cannot be parsed at all, which the installer's rejection would not tell
 * us in time to delete the file.
 *
 * Key rotation is deliberately not accommodated: if the app is ever re-signed
 * with a rotated key, self-update stops until the rotated build is installed by
 * hand. Accepting a rotation history here would mean trusting the downloaded
 * file to describe its own provenance.
 */
object ApkSignatureGuard {

    private const val TAG = "ApkSignatureGuard"

    /**
     * Set comparison over certificate digests. Split out from the Android calls
     * so the decision itself can be tested on a plain JVM.
     *
     * Empty on either side means "could not read the signers", which is a
     * mismatch, not a pass.
     */
    fun signersMatch(installed: List<ByteArray>, downloaded: List<ByteArray>): Boolean {
        if (installed.isEmpty() || downloaded.isEmpty()) return false
        return installed.map { it.sha256Hex() }.toSet() == downloaded.map { it.sha256Hex() }.toSet()
    }

    /**
     * @return true when [apk] is signed by exactly the same certificate(s) as the
     *         currently installed app.
     */
    fun verify(context: Context, apk: File): Boolean {
        val pm = context.packageManager

        val installed = try {
            signersOfInstalled(pm, context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read the signature of the installed app", e)
            return false
        }

        val downloaded = try {
            signersOfArchive(pm, apk.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read the signature of ${apk.name}", e)
            return false
        }

        val matches = signersMatch(installed, downloaded)
        if (!matches) {
            Log.e(
                TAG,
                "Signature mismatch on ${apk.name}: installed=${digestsOf(installed)} " +
                    "downloaded=${digestsOf(downloaded)}"
            )
        }
        return matches
    }

    @Suppress("DEPRECATION")
    private fun signersOfInstalled(pm: PackageManager, packageName: String): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signers(pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES))
        } else {
            signers(pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES))
        }

    @Suppress("DEPRECATION")
    private fun signersOfArchive(pm: PackageManager, path: String): List<ByteArray> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(path, flags)
        if (info == null) {
            Log.e(TAG, "Package manager could not parse $path as an APK")
            return emptyList()
        }
        return signers(info)
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<ByteArray> {
        val signatures: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            }
        return signatures.orEmpty().filterNotNull().map { it.toByteArray() }
    }

    private fun digestsOf(signers: List<ByteArray>): String =
        if (signers.isEmpty()) "<none>" else signers.joinToString(",") { it.sha256Hex().take(16) }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
