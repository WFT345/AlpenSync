// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.core.api.http.AppVersionRejectedException
import app.alpensync.core.api.http.HumanVerificationRequiredException
import app.alpensync.core.keys.KeyringUnlockException
import java.io.IOException

/**
 * How a failed sync run maps onto `SyncResult` (ADR 0005 Section 5's error
 * taxonomy), as a pure function so the mapping is testable offline and lives
 * in exactly one place.
 *
 * It exists because the taxonomy used to be a chain of `catch` clauses with
 * no final arm: anything unlisted escaped `onPerformSync` and killed the
 * process. That is not hypothetical — the framework cancels a sync by
 * interrupting the sync thread, `runBlocking` turns that into an
 * `InterruptedException`, and the post-login initial sync crashed the app
 * (2026-08-12). [classify] is total: every Throwable gets an arm.
 */
internal enum class SyncFailure {

    /**
     * Re-auth or key material needed. Counted as an auth exception so the
     * framework stops retrying — retrying cannot fix it.
     */
    AUTH,

    /** Transport or provider failure: a soft error, retried with backoff. */
    IO,

    /**
     * The framework asked us to stop. Not our error and not a "success"
     * either: the run was incomplete, so it is reported as a soft error to
     * be retried. The interrupt flag MUST be restored by the caller —
     * swallowing it silently leaves the thread's cancellation invisible.
     */
    CANCELLED,

    /** Outside the taxonomy. Contained, counted, and never fatal. */
    UNEXPECTED,

    ;

    companion object {

        fun classify(t: Throwable): SyncFailure = when (t) {
            is HumanVerificationRequiredException -> AUTH
            is AppVersionRejectedException -> AUTH
            is KeyringUnlockException -> AUTH
            is InterruptedException -> CANCELLED
            // HumanVerificationRequiredException extends IOException, so this
            // arm must stay below the specific ones above.
            is IOException -> IO
            else -> UNEXPECTED
        }
    }
}
