// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// In-test PGP key/message factory for :core:keys. Follows the same approach
// as :core:auth's TestKeyGen (adapted from pcontacts, GPL-3.0, @ bf9b0c5,
// path core/crypto/src/test/.../openpgp/TestKeyGen.kt), extended with an
// encryption subkey — real Proton rings are split (sign/certify primary +
// encrypt subkey) and the unlock code must handle every key in the ring.

package app.alpensync.core.keys

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

internal object TestPgp {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * RSA-2048 secret key ring: a sign/certify primary plus — when
     * [withSubkey] — an encryption subkey, the whole ring passphrase-protected
     * like Proton's `PrivateKey` armored blocks.
     */
    fun generateRing(
        passphrase: CharArray,
        withSubkey: Boolean = true,
        identity: String = "alpensync-keys-test",
    ): PGPSecretKeyRing {
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
        if (withSubkey) {
            gen.addSubKey(JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, rsa.generateKeyPair(), Date()))
        }
        return gen.generateSecretKeyRing()
    }

    /** Armored `-----BEGIN PGP PRIVATE KEY BLOCK-----` export, the `PrivateKey` DTO shape. */
    fun armoredSecret(ring: PGPSecretKeyRing): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armored -> ring.encode(armored) }
        return out.toString(Charsets.US_ASCII)
    }

    /** The ring's encryption subkey public key (second key), or the primary when none. */
    fun encryptionPublicKey(ring: PGPSecretKeyRing): PGPPublicKey {
        val keys = ring.secretKeys.asSequence().toList()
        return (keys.getOrNull(1) ?: ring.secretKey).publicKey
    }

    /**
     * Armored OpenPGP message (literal data) encrypted to [publicKey] — the
     * shape of an address-key `Token` blob (research notes Section 5.3).
     */
    fun encryptArmored(plaintext: ByteArray, publicKey: PGPPublicKey): String {
        val encGen = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom()),
        )
        encGen.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(publicKey))
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armored ->
            encGen.open(armored, ByteArray(PARTIAL_BUFFER)).use { encOut ->
                PGPLiteralDataGenerator()
                    .open(encOut, PGPLiteralData.BINARY, "token", plaintext.size.toLong(), Date())
                    .use { litOut -> litOut.write(plaintext) }
            }
        }
        return out.toString(Charsets.US_ASCII)
    }

    private const val RSA_BITS = 2048
    private const val PARTIAL_BUFFER = 1 shl 12
}
