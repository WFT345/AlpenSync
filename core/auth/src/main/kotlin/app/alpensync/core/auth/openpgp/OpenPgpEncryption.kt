// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/.../openpgp/BouncyCastleOpenPgpService.kt
// (encryptAndSignDetached only, lines 40-73). Deviation: standalone object
// next to OpenPgpSignatures (our OpenPgpService seam is split: sign/verify
// here in :core:auth, unlock/decrypt in :core:keys). The construction is
// copied verbatim per plan Rule 4 (no hand-rolled crypto): AES-256 with
// integrity packet, ZIP compression, detached SHA-512 binary-document
// signature over the exact plaintext bytes.

package app.alpensync.core.auth.openpgp

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator

/** The two halves of an encrypt+sign: the armored OpenPGP message and its armored detached signature. */
data class EncryptedSignedResult(
    val armoredMessage: String,
    val armoredDetachedSignature: String,
)

/**
 * OpenPGP encryption for the M3 contacts write path. Contacts are
 * self-encrypted: [encryptAndSignDetached] targets the user primary key's
 * encryption publics and signs with the user primary private key (both
 * references agree — research notes Section 1.2).
 *
 * The detached signature is BINARY-mode over the exact plaintext bytes
 * (matching the M2 read path, which verifies ENCRYPTED_AND_SIGNED cards in
 * binary mode before any vCard parsing — re-serialization would drift
 * whitespace).
 */
object OpenPgpEncryption {

    /**
     * Encrypts [plaintext] to every key in [encryptionKeys] and produces a
     * detached signature by [signingKey] over the exact plaintext bytes.
     */
    fun encryptAndSignDetached(
        plaintext: ByteArray,
        encryptionKeys: List<PgpPublicKeyHandle>,
        signingKey: PgpPrivateKeyHandle,
    ): EncryptedSignedResult {
        PgpProvider.ensureProvider()
        require(encryptionKeys.isNotEmpty()) { "at least one encryption key required" }

        val literalOut = ByteArrayOutputStream()
        PGPLiteralDataGenerator()
            .open(literalOut, PGPLiteralData.BINARY, "_", plaintext.size.toLong(), java.util.Date())
            .use { it.write(plaintext) }

        val compressedOut = ByteArrayOutputStream()
        PGPCompressedDataGenerator(PGPCompressedData.ZIP)
            .open(compressedOut).use { it.write(literalOut.toByteArray()) }

        val encryptedOut = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(encryptedOut)
        val encGen = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom()),
        )
        encryptionKeys.forEach { encGen.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(it.raw)) }
        encGen.open(armored, compressedOut.size().toLong()).use { it.write(compressedOut.toByteArray()) }
        armored.close()

        val detachedSig = OpenPgpSignatures.signDetached(
            plaintext = plaintext,
            signingKey = signingKey,
            canonicalText = false,
            stripTrailingSpaces = false,
        )
        return EncryptedSignedResult(
            armoredMessage = encryptedOut.toByteArray().toString(Charsets.US_ASCII),
            armoredDetachedSignature = detachedSig,
        )
    }
}
