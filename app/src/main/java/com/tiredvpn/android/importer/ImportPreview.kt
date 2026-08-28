package com.tiredvpn.android.importer

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tiredvpn.android.R

/**
 * The one dialog every import path goes through.
 *
 * It shows the plan that will be written - not a re-description of the payload -
 * so what the user reads and what lands in storage cannot drift apart.
 */
object ImportPreview {

    /** How many servers to name individually before collapsing into a count. */
    private const val MAX_LISTED = 8

    /**
     * @param fromExternalSource the payload arrived over a deep link, a share, or
     *   adb, i.e. from something that is not the user typing into this app.
     * @param onDone called with the result after a write, or with null if the
     *   user cancelled or there was nothing to import.
     */
    fun show(
        activity: Activity,
        parsed: ConfigCodec.ParseResult,
        fromExternalSource: Boolean,
        onDone: (ConfigImporter.Result?) -> Unit = {},
    ) {
        val plan = ConfigImporter.plan(activity, parsed)

        if (!plan.hasWork) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.import_title)
                .setMessage(emptyMessage(activity, parsed, plan))
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { onDone(null) }
                .show()
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.import_title)
            .setMessage(planMessage(activity, plan, fromExternalSource))
            .setPositiveButton(R.string.restore_import) { _, _ ->
                val result = ConfigImporter.apply(activity, plan)
                Toast.makeText(activity, summary(activity, result), Toast.LENGTH_LONG).show()
                onDone(result)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone(null) }
            .setOnCancelListener { onDone(null) }
            .show()
    }

    fun summary(activity: Activity, result: ConfigImporter.Result): String =
        activity.getString(
            R.string.import_summary,
            result.added,
            result.updated,
            result.skipped,
        )

    private fun emptyMessage(
        activity: Activity,
        parsed: ConfigCodec.ParseResult,
        plan: ConfigImporter.Plan,
    ): String = buildString {
        if (plan.skipped.isEmpty()) {
            appendLine(activity.getString(R.string.import_nothing_found))
        } else {
            appendLine(activity.getString(R.string.import_nothing_usable))
            appendLine()
            appendSkipped(plan)
        }
        if (parsed.format == ConfigCodec.Format.UNKNOWN && plan.skipped.isEmpty()) {
            appendLine()
            appendLine(activity.getString(R.string.import_accepted_formats))
        }
    }.trim()

    private fun planMessage(
        activity: Activity,
        plan: ConfigImporter.Plan,
        fromExternalSource: Boolean,
    ): String = buildString {
        if (fromExternalSource) {
            appendLine(activity.getString(R.string.import_external_warning))
            appendLine()
        }

        val add = plan.toAdd
        val update = plan.toUpdate
        appendLine(
            activity.getString(R.string.import_plan_counts, add.size, update.size, plan.skipped.size)
        )
        appendLine()

        (add.map { "+ ${it.label}" } + update.map { "~ ${it.label}" })
            .take(MAX_LISTED)
            .forEach { appendLine(it) }
        val hidden = add.size + update.size - MAX_LISTED
        if (hidden > 0) appendLine(activity.getString(R.string.import_and_more, hidden))

        if (plan.skipped.isNotEmpty()) {
            appendLine()
            appendSkipped(plan)
        }
    }.trim()

    private fun StringBuilder.appendSkipped(plan: ConfigImporter.Plan) {
        plan.skipped.take(MAX_LISTED).forEach { appendLine("- ${it.label}: ${it.reason}") }
    }
}
