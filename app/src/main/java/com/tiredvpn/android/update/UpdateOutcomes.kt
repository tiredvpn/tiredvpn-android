package com.tiredvpn.android.update

import java.io.File

/**
 * Whether retrying the same step can plausibly succeed.
 *
 * The distinction exists because [UpdateWorker] used to answer `Result.retry()`
 * to everything, so a 404 in the manifest or a SHA-256 mismatch was rescheduled
 * forever alongside genuine network flakiness.
 */
enum class FailureKind {
    /** Network hiccup, 5xx, throttling — worth another attempt later. */
    TRANSIENT,

    /** Bad manifest, missing APK, hash mismatch — retrying changes nothing. */
    PERMANENT
}

/**
 * Outcome of a version check. Replaces a nullable [UpdateConfig], where "the
 * server said no" and "we never got an answer" looked identical to the caller.
 */
sealed class VersionCheckResult {

    /** An update exists and this device can run it. */
    data class UpdateAvailable(val config: UpdateConfig) : VersionCheckResult()

    /** The server answered and there is nothing newer to install. */
    object UpToDate : VersionCheckResult()

    /** No update channel is configured in this build. */
    object NotConfigured : VersionCheckResult()

    /** The check itself did not complete. */
    data class Failed(val kind: FailureKind, val reason: String) : VersionCheckResult()
}

/** Outcome of an APK download, carrying why it failed rather than a bare null. */
sealed class DownloadOutcome {

    /** File is on disk and its SHA-256 matches the manifest. */
    data class Success(val file: File) : DownloadOutcome()

    data class Failed(val kind: FailureKind, val reason: String) : DownloadOutcome()
}

/**
 * HTTP status to retry policy. 408 and 429 are the two 4xx codes that are about
 * timing rather than about the request being wrong.
 */
internal fun failureKindForHttp(code: Int): FailureKind = when {
    code == 408 || code == 429 -> FailureKind.TRANSIENT
    code >= 500 -> FailureKind.TRANSIENT
    else -> FailureKind.PERMANENT
}
