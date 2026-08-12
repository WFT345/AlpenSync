// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/.../openpgp/BouncyCastleOpenPgpService.kt (decryptToBytes only).
// Deviation: encryption lives with the M2 contacts writer; M1 needs only the
// decrypt half (address-key Token blobs, research notes Section 5.3).

package app.alpensync.core.keys

import app.alpensync.core.auth.openpgp.PgpPrivateKeyHandle
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory

class TokenDecryptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decrypts an ASCII-armored OpenPGP message with the first matching key
 * from a set of private keys. M1 consumer: address-key `Token` blobs,
 * which are encrypted to the user's primary key and whose plaintext IS the
 * address-key passphrase (WebClients `decryptAddressKeyToken`).
 */
object TokenDecryptor {

    /**
     * @return the decrypted plaintext bytes.
     * @throws TokenDecryptException when no key matches or the message is malformed.
     */
    fun decrypt(armoredMessage: String, decryptionKeys: List<PgpPrivateKeyHandle>): ByteArray {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        require(decryptionKeys.isNotEmpty()) { "at least one decryption key required" }
        return try {
            decryptToBytes(armoredMessage, decryptionKeys)
        } catch (e: java.io.IOException) {
            throw TokenDecryptException("failed to parse armored message", e)
        } catch (e: org.bouncycastle.openpgp.PGPException) {
            throw TokenDecryptException("decryption failed", e)
        } catch (e: IllegalStateException) {
            throw TokenDecryptException(e.message ?: "decryption failed", e)
        }
    }

    private fun decryptToBytes(armoredMessage: String, decryptionKeys: List<PgpPrivateKeyHandle>): ByteArray {
        val decoded: InputStream = PGPUtil.getDecoderStream(
            ByteArrayInputStream(armoredMessage.toByteArray(Charsets.US_ASCII)),
        )
        var objectFactory: PGPObjectFactory = BcPGPObjectFactory(decoded)

        val encList = (objectFactory.nextObject() as? PGPEncryptedDataList)
            ?: throw TokenDecryptException("expected PGPEncryptedDataList at top of message")

        val keyById = decryptionKeys.associateBy { it.raw.keyID }
        val matched = encList.encryptedDataObjects.asSequence()
            .filterIsInstance<PGPPublicKeyEncryptedData>()
            .mapNotNull { enc -> keyById[enc.keyID]?.let { enc to it } }
            .firstOrNull()
            ?: throw TokenDecryptException("no encrypted data block for any of our keys")

        val clearStream = matched.first.getDataStream(BcPublicKeyDataDecryptorFactory(matched.second.raw))
        objectFactory = BcPGPObjectFactory(clearStream)

        // Strip layers: optional Compressed, then Literal.
        var packet = objectFactory.nextObject()
        if (packet is PGPCompressedData) {
            objectFactory = BcPGPObjectFactory(packet.dataStream)
            packet = objectFactory.nextObject()
        }
        val literal = packet as? PGPLiteralData
            ?: throw TokenDecryptException("expected PGPLiteralData")
        return literal.inputStream.readBytes()
    }
}
