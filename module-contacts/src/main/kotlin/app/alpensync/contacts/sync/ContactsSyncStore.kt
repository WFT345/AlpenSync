// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.contacts.store.CanonicalVCardStore
import app.alpensync.core.db.AlpenSyncDatabase

/**
 * The local persistence surface every M3b sync engine shares: the Room
 * mapping/outbox/conflict stores plus the Keystore-wrapped canonical vCard
 * store (ADR 0007 Section 5(i)). One holder so each engine's constructor
 * stays inside plan Rule 16's parameter limit.
 */
class ContactsSyncStore(val db: AlpenSyncDatabase, val canonical: CanonicalVCardStore)
