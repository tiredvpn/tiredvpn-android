package com.tiredvpn.android.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The retry policy on its own.
 *
 * [UpdateWorker] turns these into Result.retry() or Result.failure(); before the
 * split it answered retry() to everything, so a 404 or a hash mismatch was
 * rescheduled for as long as the app stayed installed.
 */
class UpdateOutcomesTest {

    @Test
    fun `client errors are permanent`() {
        listOf(400, 401, 403, 404, 410, 451).forEach { code ->
            assertEquals("HTTP $code", FailureKind.PERMANENT, failureKindForHttp(code))
        }
    }

    @Test
    fun `server errors are transient`() {
        listOf(500, 502, 503, 504).forEach { code ->
            assertEquals("HTTP $code", FailureKind.TRANSIENT, failureKindForHttp(code))
        }
    }

    @Test
    fun `timeout and throttling are transient despite being 4xx`() {
        assertEquals(FailureKind.TRANSIENT, failureKindForHttp(408))
        assertEquals(FailureKind.TRANSIENT, failureKindForHttp(429))
    }
}
