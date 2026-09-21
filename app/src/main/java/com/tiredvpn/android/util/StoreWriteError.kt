package com.tiredvpn.android.util

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.tiredvpn.android.R
import com.tiredvpn.android.vpn.ServerRepository

/**
 * Telling the user that a write did not land.
 *
 * ServerRepository.saveServer, deleteServer and setActiveServerId return false
 * when the store refuses the transaction. Every caller dropped that value, so a
 * refused save and a successful one looked identical on screen: the dialog
 * closed, the row was drawn from the config in memory, and the change was gone
 * on the next launch. For a secret the user has just pasted in, that is data
 * loss reported as success.
 *
 * A toast rather than a dialog, deliberately. ErrorDialogHelper is the
 * connection-failure dialog - a title about the VPN, a Retry that reconnects -
 * and nothing here is about connecting. There is also nothing to retry: the
 * same write against the same store fails the same way, and the useful action
 * ("your encrypted storage is broken, and here is what it said") is a sentence,
 * not a button.
 */
object StoreWriteError {

    /**
     * Why the write was refused, in one clause that reads inside the message.
     *
     * Two causes, told apart by whether the repository is already writing in
     * plaintext: a Keystore that will not open is a different problem from a
     * commit that came back false on a store that is otherwise fine, and the
     * first one names itself.
     */
    fun reason(context: Context): String =
        if (ServerRepository.isStorageDegraded) {
            context.getString(
                R.string.store_reason_degraded,
                ServerRepository.storageDegradationReason
                    ?: context.getString(R.string.store_reason_unknown),
            )
        } else {
            context.getString(R.string.store_reason_refused)
        }

    /** Show [message], with the cause filled into its one format argument. */
    fun toast(context: Context, @StringRes message: Int) {
        Toast.makeText(
            context,
            context.getString(message, reason(context)),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** [toast] unless [ok]. Returns [ok], so a caller can keep going or stop. */
    fun unless(ok: Boolean, context: Context, @StringRes message: Int): Boolean {
        if (!ok) toast(context, message)
        return ok
    }
}
