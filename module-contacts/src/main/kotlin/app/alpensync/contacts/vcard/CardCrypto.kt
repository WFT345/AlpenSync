// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// paths core/proton-contacts/.../protoncontacts/CardCrypto.kt and
// core/sync/.../contacts/decrypt/OpenPgpCardCryptoOp.kt (merged into one file).
// Deviation: production op delegates to :core:keys TokenDecryptor (the M1
// decrypt path) and :core:auth OpenPgpSignatures instead of pcontacts'
// :core:crypto OpenPgpService — same BouncyCastle primitives, already-reviewed
// call sites reused (plan Rule 4).

package app.alpensync.contacts.vcard

import app.alpensync.core.auth.openpgp.OpenPgpSignatures
import app.alpensync.core.auth.openpgp.PgpPrivateKeyHandle
import app.alpensync.core.auth.openpgp.PgpPublicKeyHandle
import app.alpensync.core.auth.openpgp.VerificationStatus
import app.alpensync.core.keys.TokenDecryptException
import app.alpensync.core.keys.TokenDecryptor

/** A card's OpenPGP payload could not be decrypted (wrong key set, corrupt armor). */
class CardDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The crypto operation one card type drives. The dispatcher knows which
 * variant to invoke; bundling the kind into the request keeps the production
 * implementation exhaustive.
 */
sealed interface CardCryptoRequest {
    /** SIGNED card — [data] is plaintext; verify the detached [signature] over it. */
    data class VerifyOnly(val data: String, val signature: String) : CardCryptoRequest

    /** ENCRYPTED card — [armored] is the OpenPGP message; no signature path. */
    data class DecryptOnly(val armored: String) : CardCryptoRequest

    /** ENCRYPTED_AND_SIGNED card — decrypt, then verify [signature] over the plaintext. */
    data class DecryptAndVerify(val armored: String, val signature: String) : CardCryptoRequest
}

/** [plaintext] is the readable vCard fragment; [verified] is the signature verdict. */
data class CardCryptoOutcome(
    val plaintext: String,
    val verified: Boolean,
)

/**
 * Production card crypto. Decryption fans out over the WHOLE unlocked key
 * set the caller passes in — all active user-key subkeys plus all active
 * address keys, as assembled by `:core:keys` KeyringUnlocker (contacts are
 * encrypted to ANY of them; single-key decryption was pcontacts' ADR-0020
 * production bug, research notes Section 2.1).
 *
 * Verification modes follow the live-verified pcontacts split (research
 * notes Section 2.3):
 *   - SIGNED cards (server-signed): canonical text mode, trailing spaces
 *     stripped.
 *   - ENCRYPTED_AND_SIGNED cards (client-signed): binary mode over the exact
 *     decrypted bytes — signatures cover the precise byte stream, verified
 *     BEFORE any vCard parsing (re-serialization would drift whitespace).
 *
 * The returned lambda closes over live key material; the caller scopes it to
 * one sync run and drops it afterwards (ADR 0004 Section 7).
 */
object OpenPgpCardCrypto {

    fun build(
        decryptionKeys: List<PgpPrivateKeyHandle>,
        verificationKeys: List<PgpPublicKeyHandle>,
    ): (CardCryptoRequest) -> CardCryptoOutcome = { request ->
        when (request) {
            is CardCryptoRequest.VerifyOnly -> CardCryptoOutcome(
                plaintext = request.data,
                verified = verifySafely(
                    request.data.toByteArray(Charsets.UTF_8),
                    request.signature,
                    verificationKeys,
                    canonicalText = true,
                    stripTrailingSpaces = true,
                ),
            )
            is CardCryptoRequest.DecryptOnly -> CardCryptoOutcome(
                plaintext = decrypt(request.armored, decryptionKeys),
                verified = true,
            )
            is CardCryptoRequest.DecryptAndVerify -> {
                val plaintext = decrypt(request.armored, decryptionKeys)
                CardCryptoOutcome(
                    plaintext = plaintext,
                    verified = verifySafely(
                        plaintext.toByteArray(Charsets.UTF_8),
                        request.signature,
                        verificationKeys,
                        canonicalText = false,
                        stripTrailingSpaces = false,
                    ),
                )
            }
        }
    }

    /**
     * A signature blob that cannot even be parsed (truncated base64, wrong
     * armor) IS a verification failure: retain the plaintext as unverified
     * rather than crash the sync. BouncyCastle raises different unchecked
     * exception types per corruption, so plan Rule 5 fail-closed demands the
     * broad catch here.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun verifySafely(
        plaintext: ByteArray,
        armoredSignature: String,
        verificationKeys: List<PgpPublicKeyHandle>,
        canonicalText: Boolean,
        stripTrailingSpaces: Boolean,
    ): Boolean = try {
        OpenPgpSignatures.verifyDetached(
            plaintext = plaintext,
            armoredSignature = armoredSignature,
            verificationKeys = verificationKeys,
            canonicalText = canonicalText,
            stripTrailingSpaces = stripTrailingSpaces,
        ) == VerificationStatus.SIGNED_AND_VALID
    } catch (ignored: Exception) {
        false
    }

    private fun decrypt(armored: String, decryptionKeys: List<PgpPrivateKeyHandle>): String {
        if (decryptionKeys.isEmpty()) throw CardDecryptException("no decryption keys available")
        val plaintext = try {
            TokenDecryptor.decrypt(armored, decryptionKeys)
        } catch (e: TokenDecryptException) {
            throw CardDecryptException("card decryption failed", e)
        }
        return String(plaintext, Charsets.UTF_8)
    }
}
