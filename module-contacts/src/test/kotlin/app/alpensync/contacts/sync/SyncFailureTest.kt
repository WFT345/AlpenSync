// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.core.api.http.AppVersionRejectedException
import app.alpensync.core.api.http.HumanVerificationRequiredException
import app.alpensync.core.keys.KeyringUnlockException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sync error taxonomy (ADR 0005 Section 5). [SyncFailure.classify] must
 * be TOTAL: anything it fails to name escapes `onPerformSync` and takes the
 * process down with it, which is exactly how an InterruptedException crashed
 * the app on the post-login initial sync (2026-08-12).
 */
class SyncFailureTest {

    @Test fun `framework cancellation is CANCELLED, not a crash — the regression`() {
        // The framework cancels a sync by interrupting the sync thread;
        // runBlocking surfaces that as InterruptedException. It used to
        // match no catch clause at all.
        assertEquals(SyncFailure.CANCELLED, SyncFailure.classify(InterruptedException()))
    }

    @Test fun `re-auth and key failures stop the framework retrying`() {
        assertEquals(SyncFailure.AUTH, SyncFailure.classify(HumanVerificationRequiredException()))
        assertEquals(SyncFailure.AUTH, SyncFailure.classify(AppVersionRejectedException(protonCode = 5003)))
        assertEquals(SyncFailure.AUTH, SyncFailure.classify(KeyringUnlockException("primary key locked")))
    }

    @Test fun `human verification outranks its IOException supertype`() {
        // HumanVerificationRequiredException IS an IOException; ordering the
        // arms wrong would silently downgrade it to a retryable soft error.
        val hv: IOException = HumanVerificationRequiredException()
        assertEquals(SyncFailure.AUTH, SyncFailure.classify(hv))
    }

    @Test fun `transport failures are retryable IO`() {
        assertEquals(SyncFailure.IO, SyncFailure.classify(IOException("socket closed")))
    }

    @Test fun `anything unlisted is contained, never fatal`() {
        // The whole point: no Throwable may fall through to the framework.
        listOf(
            IllegalStateException("bad state"),
            NullPointerException(),
            OutOfMemoryError(),
            RuntimeException(),
        ).forEach { assertEquals("$it must be contained", SyncFailure.UNEXPECTED, SyncFailure.classify(it)) }
    }
}
