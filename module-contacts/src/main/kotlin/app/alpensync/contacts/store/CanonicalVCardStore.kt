// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.store

import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.auth.store.KeystoreAesGcmKek
import app.alpensync.core.db.dao.CanonicalVCardDao
import app.alpensync.core.db.entity.CanonicalVCardEntity
import java.security.GeneralSecurityException

/** The canonical store is unreadable for this account (key invalidated, corruption). Fail-closed. */
class CanonicalStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The encrypted canonical-vCard store (ADR 0007 Section 5, storage seam
 * resolved as (i)): the last-synced canonical vCard per contact, persisted
 * so the write path can re-serialize losslessly and the ADR 0006 Option-B
 * merge has a real ancestor.
 *
 * Decrypted contact content at rest is the threat-model change this class
 * contains (THREAT_MODEL.md): every byte is wrapped with a per-account
 * Keystore-backed AES-256-GCM key — the exact construction of the M1 token
 * store ([KeystoreAesGcmKek], reused, not duplicated). Plaintext never
 * touches disk and is never logged; [wipeAccount] deletes the rows AND the
 * KEK alias (logout/account removal).
 *
 * The wrap/unwrap/deleteKey seam exists so unit tests drive real AES-GCM
 * with in-JVM keys (incl. the wrong-key case) without an Android Keystore.
 */
class CanonicalVCardStore internal constructor(
    private val dao: CanonicalVCardDao,
    private val wrap: (ByteArray) -> ByteArray,
    private val unwrap: (ByteArray) -> ByteArray,
    private val deleteKey: () -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * The canonical vCard 4.0 text, or null when none is stored.
     * Throws [CanonicalStoreException] on undecryptable data.
     */
    // Keystore surfaces some failures unchecked (e.g.
    // KeyPermanentlyInvalidatedException), so the catch cannot be narrowed.
    @Suppress("TooGenericExceptionCaught")
    suspend fun read(account: String, protonContactId: String): String? {
        val row = dao.find(account, protonContactId) ?: return null
        val plaintext = try {
            unwrap(row.vcardEnc)
        } catch (e: GeneralSecurityException) {
            throw unwrapFailed(e)
        } catch (e: RuntimeException) {
            // The boundary stays total (Hard Rules): nothing propagates raw.
            throw unwrapFailed(e)
        }
        return String(plaintext, Charsets.UTF_8)
    }

    suspend fun write(account: String, protonContactId: String, canonicalVCardText: String) {
        dao.upsert(
            CanonicalVCardEntity(
                accountName = account,
                protonContactId = protonContactId,
                vcardEnc = wrap(canonicalVCardText.toByteArray(Charsets.UTF_8)),
                updatedAt = nowMs(),
            ),
        )
    }

    suspend fun delete(account: String, protonContactId: String) = dao.delete(account, protonContactId)

    /** Existence probe without decrypting (the pull engine's canonical-backfill check). */
    suspend fun exists(account: String, protonContactId: String): Boolean =
        dao.find(account, protonContactId) != null

    /**
     * Wraps a conflict-copy payload under the same per-account KEK (ADR 0007
     * Section 7): the losing side's canonical vCard, never plaintext at rest.
     */
    fun encryptPayload(plaintext: ByteArray): ByteArray = wrap(plaintext)

    /** Logout/account removal: ciphertext rows AND the KEK alias go (THREAT_MODEL.md). */
    suspend fun wipeAccount(account: String) {
        dao.deleteAllForAccount(account)
        deleteKey()
    }

    private fun unwrapFailed(cause: Exception): CanonicalStoreException {
        SafeLog.log(SafeLog.Event.CANONICAL_STORE_UNWRAP_FAILED)
        return CanonicalStoreException("canonical vCard undecryptable (key invalidated or store corrupted)", cause)
    }

    companion object {
        private const val KEK_ALIAS_PREFIX = "alpensync.vcard.kek."

        /** Production construction: wraps under a per-account Keystore key, exactly the token store's pattern. */
        fun create(dao: CanonicalVCardDao, accountId: String): CanonicalVCardStore {
            val kek = KeystoreAesGcmKek(KEK_ALIAS_PREFIX + sanitize(accountId))
            return CanonicalVCardStore(dao, kek::wrap, kek::unwrap, kek::delete)
        }

        /** Same account-id rule as the M1 secret store: never let a hostile value escape the alias namespace. */
        private fun sanitize(accountId: String): String {
            val cleaned = accountId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            require(cleaned.isNotEmpty()) { "accountId must contain at least one safe character" }
            return cleaned
        }
    }
}
