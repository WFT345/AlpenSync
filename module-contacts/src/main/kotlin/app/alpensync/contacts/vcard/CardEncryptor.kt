// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// paths core/proton-contacts/.../CardEncryptOp.kt (request/outcome seam) and
// core/sync/.../contacts/encrypt/OpenPgpCardEncryptOp.kt (production op).
// Deviation: delegates to :core:auth OpenPgpEncryption/OpenPgpSignatures
// instead of pcontacts' :core:crypto OpenPgpService — the mirror image of
// our M2 CardCrypto wiring.

package app.alpensync.contacts.vcard

import app.alpensync.core.auth.openpgp.OpenPgpEncryption
import app.alpensync.core.auth.openpgp.OpenPgpSignatures
import app.alpensync.core.auth.openpgp.PgpPrivateKeyHandle
import app.alpensync.core.auth.openpgp.PgpPublicKeyHandle

/** A card's OpenPGP write ceremony failed (key problem, BC internals). Never carries content. */
class CardEncryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The write-path counterpart of [CardCryptoRequest]: which ceremony one card gets. */
sealed interface CardEncryptRequest {
    /** SIGNED card — plaintext stays; produce the detached signature (canonical-text mode). */
    data class SignOnly(val plaintext: String) : CardEncryptRequest

    /** ENCRYPTED_AND_SIGNED card — encrypt to our own key(s), sign the exact plaintext (binary mode). */
    data class EncryptAndSign(val plaintext: String) : CardEncryptRequest
}

/** [data] is the card body (plaintext or armored ciphertext); [signature] the armored detached signature. */
data class CardEncryptOutcome(
    val data: String,
    val signature: String?,
)

typealias CardEncryptOp = (CardEncryptRequest) -> CardEncryptOutcome

/**
 * Production card encryptor. Self-encryption (research notes Section 1.2):
 * [encryptionKeys] are the user primary key's encryption publics,
 * [signingKey] is the user primary private key — the full fan-out of the
 * read path is NOT needed on write.
 *
 * Signature modes are the exact inverse of the M2 read path: SIGNED cards
 * canonical-text (trailing spaces stripped), ENCRYPTED_AND_SIGNED binary
 * over the exact bytes — so our own next pull verifies what we wrote.
 *
 * The returned lambda closes over live key material; the caller scopes it to
 * one sync run and drops it afterwards (ADR 0004 Section 7).
 */
object OpenPgpCardEncryptor {

    fun build(
        encryptionKeys: List<PgpPublicKeyHandle>,
        signingKey: PgpPrivateKeyHandle,
    ): CardEncryptOp = { request ->
        when (request) {
            is CardEncryptRequest.SignOnly -> CardEncryptOutcome(
                data = request.plaintext,
                signature = sign(request.plaintext, signingKey),
            )
            is CardEncryptRequest.EncryptAndSign -> encryptAndSign(
                request.plaintext,
                encryptionKeys,
                signingKey,
            )
        }
    }

    // The BouncyCastle exception vocabulary (PGPException et al.) is not on
    // this module's compile classpath — :core:auth exposes BC only via its
    // handle types. The boundary must still be total (Hard Rules), so any
    // failure of the crypto layer is wrapped, never propagated raw.
    @Suppress("TooGenericExceptionCaught")
    private fun sign(plaintext: String, signingKey: PgpPrivateKeyHandle): String = try {
        OpenPgpSignatures.signDetached(
            plaintext = plaintext.toByteArray(Charsets.UTF_8),
            signingKey = signingKey,
            canonicalText = true,
            stripTrailingSpaces = true,
        )
    } catch (e: Exception) {
        throw CardEncryptException("card signing failed", e)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun encryptAndSign(
        plaintext: String,
        encryptionKeys: List<PgpPublicKeyHandle>,
        signingKey: PgpPrivateKeyHandle,
    ): CardEncryptOutcome {
        val result = try {
            OpenPgpEncryption.encryptAndSignDetached(
                plaintext = plaintext.toByteArray(Charsets.UTF_8),
                encryptionKeys = encryptionKeys,
                signingKey = signingKey,
            )
        } catch (e: Exception) {
            throw CardEncryptException("card encryption failed", e)
        }
        return CardEncryptOutcome(
            data = result.armoredMessage,
            signature = result.armoredDetachedSignature,
        )
    }
}
