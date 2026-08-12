// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/main/kotlin/io/pcontacts/core/crypto/srp/ProtonModulusVerifier.kt
// Deviation: verifies through the trimmed OpenPgpSignatures surface; the
// outcome mapping (VALID / INVALID / NO_SIGNER_KEY, all fail-closed except
// VALID) is unchanged.

package app.alpensync.core.auth.srp

import app.alpensync.core.auth.openpgp.OpenPgpSignatures
import app.alpensync.core.auth.openpgp.PgpProvider
import app.alpensync.core.auth.openpgp.PgpPublicKeyHandle
import app.alpensync.core.auth.openpgp.VerificationStatus
import java.io.ByteArrayInputStream
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory

/**
 * Verifies the OpenPGP detached signature on the SRP `Modulus` field
 * against a pinned Proton SRP signing public key (fail-closed).
 *
 * Outcomes:
 *   VALID          — signature checks against the pinned key; proceed.
 *   INVALID        — signature present but verification failed; the caller
 *                    MUST abort login (treat as MITM).
 *   NO_SIGNER_KEY  — no pinned key configured (missing or unparseable
 *                    resource); the caller MUST abort login. The key ships
 *                    in `proton_srp_signing_key.asc` and must load in
 *                    production builds.
 */
fun interface ProtonModulusVerifier {
    fun verify(cleartext: String, armoredSignature: String): ProtonModulusVerification
}

enum class ProtonModulusVerification {
    VALID,
    INVALID,
    NO_SIGNER_KEY,
}

class BouncyCastleProtonModulusVerifier(
    pinnedPublicKeyArmored: String?,
) : ProtonModulusVerifier {

    private val pinnedKey: PgpPublicKeyHandle? = pinnedPublicKeyArmored?.let { armored ->
        runCatching { parseFirstPublicKey(armored) }.getOrNull()
    }

    override fun verify(cleartext: String, armoredSignature: String): ProtonModulusVerification {
        val key = pinnedKey ?: return ProtonModulusVerification.NO_SIGNER_KEY
        return runCatching {
            val status = OpenPgpSignatures.verifyDetached(
                plaintext = cleartext.toByteArray(Charsets.US_ASCII),
                armoredSignature = armoredSignature,
                verificationKeys = listOf(key),
                canonicalText = true,
                stripTrailingSpaces = true,
            )
            when (status) {
                VerificationStatus.SIGNED_AND_VALID -> ProtonModulusVerification.VALID
                else -> ProtonModulusVerification.INVALID
            }
        }.getOrElse { ProtonModulusVerification.INVALID }
    }

    private fun parseFirstPublicKey(armored: String): PgpPublicKeyHandle {
        PgpProvider.ensureProvider()
        val decoded = PGPUtil.getDecoderStream(
            ByteArrayInputStream(armored.toByteArray(Charsets.US_ASCII)),
        )
        val factory = BcPGPObjectFactory(decoded)
        val ring = generateSequence { factory.nextObject() }
            .filterIsInstance<PGPPublicKeyRing>()
            .firstOrNull()
            ?: error("no PGPPublicKeyRing in pinned key armored block")
        // Proton's SRP signing key: the primary holds the signing capability.
        val pub = ring.publicKey ?: error("PGPPublicKeyRing has no primary key")
        return PgpPublicKeyHandle(raw = pub)
    }

    companion object {
        const val RESOURCE_PATH = "/proton_srp_signing_key.asc"

        /**
         * Reads the pinned key resource from the classpath. Returns null
         * if absent or unparseable (→ NO_SIGNER_KEY → login aborts).
         */
        fun loadPinnedKeyFromClasspath(): String? {
            val stream = BouncyCastleProtonModulusVerifier::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: return null
            val text = stream.use { it.readBytes() }.toString(Charsets.US_ASCII)
            return if (text.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")) text else null
        }
    }
}
