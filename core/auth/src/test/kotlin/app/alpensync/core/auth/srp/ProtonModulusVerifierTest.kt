// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/crypto/src/test/kotlin/io/pcontacts/core/crypto/srp/ProtonModulusVerifierTest.kt

package app.alpensync.core.auth.srp

import app.alpensync.core.auth.openpgp.OpenPgpSignatures
import app.alpensync.core.auth.openpgp.TestKeyGen
import java.io.ByteArrayOutputStream
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fail-closed modulus verification: a valid signature against the pinned
 * key returns VALID; tampered cleartext, a wrong-key signature, a missing
 * pin, and a malformed pin all return non-VALID outcomes (the orchestrator
 * aborts login on any of them).
 */
class ProtonModulusVerifierTest {

    @Test fun valid_signature_against_pinned_key_returns_VALID() {
        val keys = TestKeyGen.rsa2048()
        val cleartext = "AAECAwQFBgcICQoLDA0ODw=="
        val signature = OpenPgpSignatures.signDetached(
            plaintext = cleartext.toByteArray(Charsets.US_ASCII),
            signingKey = keys.priv,
        )
        val verifier = BouncyCastleProtonModulusVerifier(armoredPublicKeyOf(keys))

        assertEquals(ProtonModulusVerification.VALID, verifier.verify(cleartext, signature))
    }

    @Test fun tampered_signature_returns_INVALID() {
        val keys = TestKeyGen.rsa2048()
        val cleartext = "AAECAwQFBgcICQoLDA0ODw=="
        val signature = OpenPgpSignatures.signDetached(
            plaintext = cleartext.toByteArray(Charsets.US_ASCII),
            signingKey = keys.priv,
        )
        val verifier = BouncyCastleProtonModulusVerifier(armoredPublicKeyOf(keys))

        // A DIFFERENT cleartext can't possibly verify against the signature.
        val tamperedCleartext = "QUFFQ0F3UUZCZ2NJQ1FvTERBME9Edz09"
        assertEquals(ProtonModulusVerification.INVALID, verifier.verify(tamperedCleartext, signature))
    }

    @Test fun signature_from_a_different_key_returns_INVALID() {
        val pinnedKeys = TestKeyGen.rsa2048()
        val attackerKeys = TestKeyGen.rsa2048(identity = "attacker")
        val cleartext = "AAECAwQFBgcICQoLDA0ODw=="
        val attackerSig = OpenPgpSignatures.signDetached(
            plaintext = cleartext.toByteArray(Charsets.US_ASCII),
            signingKey = attackerKeys.priv,
        )
        val verifier = BouncyCastleProtonModulusVerifier(armoredPublicKeyOf(pinnedKeys))

        assertEquals(ProtonModulusVerification.INVALID, verifier.verify(cleartext, attackerSig))
    }

    @Test fun missing_pinned_key_returns_NO_SIGNER_KEY() {
        val verifier = BouncyCastleProtonModulusVerifier(pinnedPublicKeyArmored = null)
        assertEquals(ProtonModulusVerification.NO_SIGNER_KEY, verifier.verify("anything", "any signature"))
    }

    @Test fun malformed_pinned_key_falls_back_to_NO_SIGNER_KEY() {
        val verifier = BouncyCastleProtonModulusVerifier("this is not a PGP key block")
        assertEquals(ProtonModulusVerification.NO_SIGNER_KEY, verifier.verify("anything", "any signature"))
    }

    @Test fun loadPinnedKeyFromClasspath_returns_armored_key() {
        // proton_srp_signing_key.asc is committed — sourced from
        // ProtonMail/go-srp and cross-checked against emersion/hydroxide
        // (see core/auth/src/main/resources/README_proton_srp_signing_key.md).
        val armored = BouncyCastleProtonModulusVerifier.loadPinnedKeyFromClasspath()
        assertNotNull("pinned key resource must be present on the classpath", armored)
        assertTrue(
            "must be an armored PGP key block",
            armored!!.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"),
        )
    }

    @Test fun pinned_key_parses_and_verifies_against_real_garbage_signature() {
        val armored = BouncyCastleProtonModulusVerifier.loadPinnedKeyFromClasspath()!!
        val verifier = BouncyCastleProtonModulusVerifier(armored)
        // A garbage signature against the REAL pinned key must yield
        // INVALID (key loaded, verification failed) — not NO_SIGNER_KEY.
        val garbageSig = "-----BEGIN PGP SIGNATURE-----\n\n" +
            "iHUEARYIAB0WIQRbjk8xQkVnUqFUQOM1BYXE6VGPJgUCXAHLgwAKCRA1BYXE\n" +
            "6VGPJnrhAP9G/vQjY7gOI0nnrBYmAGIuVMhh0AAAAAAA\n=AAAA\n" +
            "-----END PGP SIGNATURE-----"
        assertEquals(ProtonModulusVerification.INVALID, verifier.verify("test", garbageSig))
    }

    private fun armoredPublicKeyOf(testKey: TestKeyGen.TestKey): String {
        val ring = PGPPublicKeyRing(listOf(testKey.pub.raw))
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { ring.encode(it) }
        return out.toString(Charsets.US_ASCII)
    }
}
