// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/main/kotlin/io/pcontacts/core/crypto/openpgp/BouncyCastleKeyUnlock.kt

package app.alpensync.core.keys

import app.alpensync.core.auth.openpgp.PgpPrivateKeyHandle
import app.alpensync.core.auth.openpgp.PgpPublicKeyHandle
import java.io.ByteArrayInputStream
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider

/**
 * Bundle of an unlocked key ring: the primary (signing) key plus every
 * private key in the ring. Callers hold this for the duration of a
 * login/sync operation and drop the references at scope exit — the private
 * key material stays sensitive in memory (ADR 0004 Section 7).
 */
data class UnlockedKey(
    val primary: PgpPrivateKeyHandle,
    val public: PgpPublicKeyHandle,
    val allPrivateKeys: List<PgpPrivateKeyHandle> = listOf(primary),
)

class KeyUnlockException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads an ASCII-armored OpenPGP secret key block and unlocks ALL secret
 * keys in the ring (primary + encryption subkeys). Real Proton accounts
 * use split keys: the primary carries SIGN_DATA | CERTIFY_OTHER, a subkey
 * carries ENCRYPT_COMMS | ENCRYPT_STORAGE, and contacts are encrypted to
 * the subkey (live-verified by pcontacts incl. subkey decryption) — so the
 * decrypt path needs every key in the ring, not just the primary.
 */
object KeyUnlock {

    /**
     * @param armoredPrivateKey the `-----BEGIN PGP PRIVATE KEY BLOCK-----` text.
     * @param passphrase the unlock passphrase (Proton's keyPassword). The
     *        caller zeroes the array; BouncyCastle gets its own copy, so
     *        zeroing on return is safe.
     * @throws KeyUnlockException if the block is malformed, contains no
     *         secret key ring, or the passphrase is wrong.
     */
    fun unlock(armoredPrivateKey: String, passphrase: CharArray): UnlockedKey {
        ensureProvider()

        val ring = parseSecretKeyRing(armoredPrivateKey)
        val decryptor = buildDecryptor(passphrase)

        val allKeys = mutableListOf<PgpPrivateKeyHandle>()
        for (sk in ring.secretKeys) {
            val priv = try {
                sk.extractPrivateKey(decryptor)
            } catch (e: PGPException) {
                throw KeyUnlockException("wrong passphrase or corrupted key material", e)
            }
            allKeys += PgpPrivateKeyHandle(raw = priv, pubKey = sk.publicKey)
        }
        if (allKeys.isEmpty()) {
            throw KeyUnlockException("key ring contains no secret keys")
        }

        val primaryKey = ring.secretKey
        return UnlockedKey(
            primary = allKeys.first { it.raw.keyID == primaryKey.keyID },
            public = PgpPublicKeyHandle(raw = primaryKey.publicKey),
            allPrivateKeys = allKeys,
        )
    }

    private fun parseSecretKeyRing(armoredPrivateKey: String): PGPSecretKeyRing {
        val decoded = PGPUtil.getDecoderStream(
            ByteArrayInputStream(armoredPrivateKey.toByteArray(Charsets.US_ASCII)),
        )
        return try {
            val factory = BcPGPObjectFactory(decoded)
            generateSequence { factory.nextObject() }
                .filterIsInstance<PGPSecretKeyRing>()
                .firstOrNull()
                ?: throw KeyUnlockException("no PGPSecretKeyRing found in armored input")
        } catch (e: java.io.IOException) {
            throw KeyUnlockException("failed to parse armored private key", e)
        }
    }

    private fun buildDecryptor(passphrase: CharArray) = try {
        BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(passphrase)
    } catch (e: PGPException) {
        throw KeyUnlockException("wrong passphrase or corrupted key material", e)
    }

    private fun ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
