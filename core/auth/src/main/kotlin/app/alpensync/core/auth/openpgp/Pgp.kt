// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/main/kotlin/io/pcontacts/core/crypto/openpgp/Pgp.kt
// Deviation: trimmed to what M1 uses (provider bootstrap, key handles,
// verification status). The full four-operation OpenPgpService lands with
// the contacts crypto at M2.

package app.alpensync.core.auth.openpgp

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey

/**
 * Security-provider bootstrap for the OpenPGP layer. Every entry point
 * runs `ensureProvider()` before touching BouncyCastle types, so callers
 * don't need to think about provider registration.
 */
internal object PgpProvider {
    @JvmStatic
    private val installed: Boolean = run {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        true
    }

    fun ensureProvider() {
        // Touching this property forces the lazy init above.
        @Suppress("UNUSED_EXPRESSION") installed
    }
}

/**
 * Wrapper around the BouncyCastle key types. The BC types stay accessible
 * (unlike pcontacts, where they were module-internal) because our crypto
 * is split across `:core:auth` (sign/verify) and `:core:keys` (unlock /
 * decrypt) — both are trusted crypto modules operating on the same handles.
 */
data class PgpPublicKeyHandle(val raw: PGPPublicKey) {
    val keyIdHex: String get() = "%016X".format(raw.keyID)
}

data class PgpPrivateKeyHandle(
    val raw: PGPPrivateKey,
    val pubKey: PGPPublicKey,
) {
    val keyIdHex: String get() = "%016X".format(raw.keyID)
}

enum class VerificationStatus {
    /** Signature present and matches one of the verification keys. */
    SIGNED_AND_VALID,

    /** Signature present but verification failed (tampered or wrong key). */
    SIGNED_INVALID,

    /** No signature on the message / call site asked for an unsigned path. */
    NOT_SIGNED,

    /** Signature present, but no supplied key matched the signer's key ID. */
    SIGNED_NO_VERIFIER,
}
