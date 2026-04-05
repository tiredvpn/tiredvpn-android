package com.tiredvpn.android.util

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tiredvpn.android.R

object ErrorDialogHelper {
    fun showConnectionError(
        context: Context,
        errorMessage: String,
        onRetry: (() -> Unit)? = null
    ) {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.connection_error_title)
            .setMessage(context.getString(R.string.connection_error_message, errorMessage))
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }

        if (onRetry != null) {
            builder.setNegativeButton(R.string.retry) { dialog, _ ->
                dialog.dismiss()
                onRetry()
            }
        }

        builder.show()
    }
}
