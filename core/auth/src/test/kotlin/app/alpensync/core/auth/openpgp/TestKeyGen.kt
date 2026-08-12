// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/test/kotlin/io/pcontacts/core/crypto/openpgp/TestKeyGen.kt

package app.alpensync.core.auth.openpgp

import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Date
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair

/**
 * Test-only key generator. Produces an RSA-2048 OpenPGP keypair (chosen
 * for portability — production code accepts whatever a real Proton account
 * hands us, including ECC). Returns ready-to-use handles.
 */
internal object TestKeyGen {

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class TestKey(val pub: PgpPublicKeyHandle, val priv: PgpPrivateKeyHandle)

    fun rsa2048(identity: String = "alpensync-test"): TestKey {
        val secretRing = generateRing(CharArray(0), identity)
        val secretKey = secretRing.secretKey
        val pgpPrivateKey = secretKey.extractPrivateKey(
            BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(CharArray(0)),
        )
        return TestKey(
            pub = PgpPublicKeyHandle(secretKey.publicKey),
            priv = PgpPrivateKeyHandle(pgpPrivateKey, secretKey.publicKey),
        )
    }

    /**
     * Generates an RSA-2048 keypair encrypted under [passphrase] and
     * returns the ASCII-armored secret key block — the same shape
     * `core/v4/users` ships in `User.Keys[i].PrivateKey`.
     */
    fun rsa2048Armored(passphrase: CharArray, identity: String = "alpensync-test"): String {
        val secretRing = generateRing(passphrase, identity)
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armored -> secretRing.encode(armored) }
        return out.toString(Charsets.US_ASCII)
    }

    /** Exports the armored PUBLIC key ring for [passphrase]-protected key. */
    fun rsa2048ArmoredPublic(passphrase: CharArray, identity: String = "alpensync-test"): String {
        val secretRing = generateRing(passphrase, identity)
        val publicRing = org.bouncycastle.openpgp.PGPPublicKeyRing(
            secretRing.publicKeys.asSequence().toList(),
        )
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armored -> publicRing.encode(armored) }
        return out.toString(Charsets.US_ASCII)
    }

    private fun generateRing(passphrase: CharArray, identity: String): PGPSecretKeyRing {
        val rsa = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        rsa.initialize(2048)
        val rsaKeyPair = rsa.generateKeyPair()
        val pgpKeyPair: PGPKeyPair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, rsaKeyPair, Date())

        // PGPSecretKey checksum calculation requires SHA-1; signing itself
        // uses SHA-512 via BcPGPContentSignerBuilder below.
        val checksumCalc = BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)
        val signSubpacket = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(
                false,
                KeyFlags.SIGN_DATA or KeyFlags.CERTIFY_OTHER or
                    KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE,
            )
            setPreferredSymmetricAlgorithms(false, intArrayOf(SymmetricKeyAlgorithmTags.AES_256))
            setPreferredHashAlgorithms(false, intArrayOf(HashAlgorithmTags.SHA512))
        }

        val keyRingGen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pgpKeyPair,
            identity,
            checksumCalc,
            signSubpacket.generate(),
            null,
            BcPGPContentSignerBuilder(pgpKeyPair.publicKey.algorithm, HashAlgorithmTags.SHA512),
            BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, checksumCalc)
                .build(passphrase),
        )
        return keyRingGen.generateSecretKeyRing()
    }
}
