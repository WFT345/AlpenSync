// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// In-test OpenPGP fixture factory for :module-contacts. Key generation
// adapted from :core:keys' TestPgp (itself adapted from pcontacts, GPL-3.0,
// @ bf9b0c5, path core/crypto/src/test/.../openpgp/TestKeyGen.kt); card
// construction mirrors how the notes describe Proton building Cards[]
// (docs/research/m2-contacts-notes.md Sections 1.3 + 6.2): AES-256 with
// integrity packet to the ring's encryption subkey, detached SHA-512
// signatures — canonical-text for server-style SIGNED cards, binary over
// exact bytes for client-style ENCRYPTED_AND_SIGNED cards.

package app.alpensync.contacts.vcard

import app.alpensync.core.auth.openpgp.OpenPgpSignatures
import app.alpensync.core.keys.KeyUnlock
import app.alpensync.core.keys.UnlockedKey
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.util.Date
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair

internal object TestCards {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /** RSA-2048 ring with a sign/certify primary plus an encryption subkey — the real Proton shape. */
    fun generateRing(passphrase: CharArray, identity: String): PGPSecretKeyRing {
        val rsa = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        rsa.initialize(RSA_BITS)
        val masterPair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, rsa.generateKeyPair(), Date())

        // PGPSecretKey checksum calculation requires SHA-1; signing uses SHA-512.
        val checksumCalc = BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)
        val gen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            masterPair,
            identity,
            checksumCalc,
            null,
            null,
            BcPGPContentSignerBuilder(masterPair.publicKey.algorithm, HashAlgorithmTags.SHA512),
            BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, checksumCalc)
                .build(passphrase),
        )
        gen.addSubKey(JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, rsa.generateKeyPair(), Date()))
        return gen.generateSecretKeyRing()
    }

    /** Unlock a ring through the production :core:keys path — exercises the real unlock code. */
    fun unlock(ring: PGPSecretKeyRing, passphrase: CharArray): UnlockedKey {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { ring.encode(it) }
        return KeyUnlock.unlock(out.toString(Charsets.US_ASCII), passphrase)
    }

    /** Armored OpenPGP message (literal data) encrypted to the ring's encryption subkey. */
    fun encryptToSubkey(plaintext: String, ring: PGPSecretKeyRing): String {
        val ringKeys = ring.secretKeys.asSequence().toList()
        val encryptionKey = (ringKeys.getOrNull(1) ?: ring.secretKey).publicKey
        val encGen = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom()),
        )
        encGen.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(encryptionKey))
        val out = ByteArrayOutputStream()
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        ArmoredOutputStream(out).use { armored ->
            encGen.open(armored, ByteArray(PARTIAL_BUFFER)).use { encOut ->
                PGPLiteralDataGenerator()
                    .open(encOut, PGPLiteralData.BINARY, "card", plaintextBytes.size.toLong(), Date())
                    .use { it.write(plaintextBytes) }
            }
        }
        return out.toString(Charsets.US_ASCII)
    }

    /** Server-style SIGNED-card signature: canonical text mode, trailing spaces stripped. */
    fun signServerStyle(plaintext: String, key: UnlockedKey): String = OpenPgpSignatures.signDetached(
        plaintext.toByteArray(Charsets.UTF_8), key.primary, canonicalText = true, stripTrailingSpaces = true,
    )

    /** Client-style card signature: binary mode over the exact plaintext bytes. */
    fun signClientStyle(plaintext: String, key: UnlockedKey): String = OpenPgpSignatures.signDetached(
        plaintext.toByteArray(Charsets.UTF_8), key.primary, canonicalText = false, stripTrailingSpaces = false,
    )

    private const val RSA_BITS = 2048
    private const val PARTIAL_BUFFER = 1 shl 12
}
