// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Pipeline shape per ADR 0007 Section 1 (pcontacts ADR-0017 Section 7B):
// detect → push → pull, the exact pcontacts ordering.

package app.alpensync.contacts.sync

/**
 * One full two-way sync run (M3b):
 *
 *   detect local changes → drain the outbox (push) → pull remote changes
 *   (merge + apply inside the engines)
 *
 * Push-first so the subsequent pull observes our own writes' new ModifyTimes
 * and converges in one run (ADR 0007 Section 1). A detector/provider failure
 * aborts before any push (a provider that cannot answer cannot be scanned);
 * a human-verification or app-version gate from the push aborts before the
 * pull (every subsequent call hits the same gate — pcontacts' shipped
 * policy). Per-entry push failures are contained inside the write engine and
 * never reach here.
 *
 * The report is the pull engine's [SyncReport] — the write side's counts are
 * carried by SafeLog events (fixed, non-secret) until the M4 sync-log surface.
 */
class ContactsSyncRunner(
    private val detector: LocalChangeDetector,
    private val writeEngine: ContactWriteEngine,
    private val pullEngine: ContactsSyncEngine,
) {

    suspend fun run(): SyncReport {
        detector.scan()
        writeEngine.push()
        return pullEngine.run()
    }
}
