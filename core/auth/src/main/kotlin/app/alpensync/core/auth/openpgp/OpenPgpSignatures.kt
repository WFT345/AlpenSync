// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// paths core/crypto/.../openpgp/BouncyCastleOpenPgpService.kt (verifyDetached /
// signDetached only) and .../openpgp/TextCanonicalization.kt (inlined below).
// Deviation: encrypt/decrypt are NOT ported here — :core:keys owns the M1
// decrypt path (address-key Token); encrypt+sign landed at M3a next door in
// OpenPgpEncryption.kt.

package app.alpensync.core.auth.openpgp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider

/**
 * OpenPGP detached-signature operations. M1 consumer: the SRP modulus
 * envelope verifier (canonical text mode). [signDetached] exists so the
 * verifier's tests can produce real signatures, and matches the shape the
 * M2 contacts signer will need.
 */
object OpenPgpSignatures {

    /** Verifies an ASCII-armored detached signature over [plaintext]. */
    fun verifyDetached(
        plaintext: ByteArray,
        armoredSignature: String,
        verificationKeys: List<PgpPublicKeyHandle>,
        canonicalText: Boolean = true,
        stripTrailingSpaces: Boolean = true,
    ): VerificationStatus {
        PgpProvider.ensureProvider()
        if (verificationKeys.isEmpty()) return VerificationStatus.SIGNED_NO_VERIFIER

        val sig = parseDetachedSignature(armoredSignature) ?: return VerificationStatus.NOT_SIGNED
        val verifier = verificationKeys.firstOrNull { it.raw.keyID == sig.keyID }
            ?: return VerificationStatus.SIGNED_NO_VERIFIER

        val data = if (canonicalText) canonicalize(plaintext, stripTrailingSpaces) else plaintext
        sig.init(BcPGPContentVerifierBuilderProvider(), verifier.raw)
        sig.update(data)
        return if (sig.verify()) VerificationStatus.SIGNED_AND_VALID else VerificationStatus.SIGNED_INVALID
    }

    /** Produces an ASCII-armored detached signature over [plaintext]. */
    fun signDetached(
        plaintext: ByteArray,
        signingKey: PgpPrivateKeyHandle,
        canonicalText: Boolean = true,
        stripTrailingSpaces: Boolean = true,
    ): String {
        PgpProvider.ensureProvider()
        val signatureType =
            if (canonicalText) PGPSignature.CANONICAL_TEXT_DOCUMENT else PGPSignature.BINARY_DOCUMENT
        val data = if (canonicalText) canonicalize(plaintext, stripTrailingSpaces) else plaintext

        val sigGen = PGPSignatureGenerator(
            BcPGPContentSignerBuilder(signingKey.pubKey.algorithm, HashAlgorithmTags.SHA512),
        )
        sigGen.init(signatureType, signingKey.raw)

        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(out)
        val bcpgOut = BCPGOutputStream(armored)
        sigGen.update(data)
        sigGen.generate().encode(bcpgOut)
        bcpgOut.close()
        armored.close()
        return out.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun parseDetachedSignature(armoredSignature: String): PGPSignature? {
        val decoded: InputStream = PGPUtil.getDecoderStream(
            ByteArrayInputStream(armoredSignature.toByteArray(Charsets.US_ASCII)),
        )
        val factory = BcPGPObjectFactory(decoded)
        val obj = factory.nextObject() ?: return null
        return when (obj) {
            is PGPSignatureList -> if (obj.isEmpty) null else obj.get(0)
            is PGPSignature -> obj
            else -> null
        }
    }

    /**
     * OpenPGP canonical text (RFC 4880 §5.2.1): CRLF line endings, and —
     * when [stripTrailingSpaces] — trailing whitespace removed per line.
     */
    private fun canonicalize(data: ByteArray, stripTrailingSpaces: Boolean): ByteArray {
        val text = data.toString(Charsets.UTF_8)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val canonical = lines.joinToString("\r\n") { line ->
            if (stripTrailingSpaces) line.trimEnd(' ', '\t') else line
        }
        return canonical.toByteArray(Charsets.UTF_8)
    }
}
