// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.auth

import app.alpensync.core.api.dto.AuthRequest
import app.alpensync.core.auth.srp.BouncyCastleProtonModulusVerifier
import app.alpensync.core.auth.srp.ProtonModulusEnvelope
import app.alpensync.core.auth.srp.ProtonModulusVerification
import app.alpensync.core.auth.srp.SrpClient
import app.alpensync.core.auth.util.toLittleEndianBytes
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan Rule 19 — pins our SRP client against pcontacts' live-verified
 * implementation (@ bf9b0c5) on a REAL captured `auth/info` response
 * (Version=4). On 2026-08-12 a differential run fed both implementations
 * identical inputs (real Salt/ServerEphemeral/Modulus, dummy password
 * `DifferentialTest#1`, fixed client secret `a` = bytes 0x01..0x20 via each
 * side's injectable `SecureRandom`) and the outputs were byte-identical at
 * every stage: x, A, M1, M2, session key, and the full `auth` POST body.
 *
 * The pinned values below are OUR outputs from that run — they equal the
 * live-verified reference's, so this test locks us to it permanently. The
 * Modulus/ServerEphemeral/Salt/SRPSession are SRP-public values from a
 * throwaway probe account; the password is a dummy. No real credentials.
 */
class LiveParamsGoldenTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `real modulus envelope verifies against the pinned key`() {
        val decoded = ProtonModulusEnvelope.decode(MODULUS_ENVELOPE)
        assertEquals(
            ProtonModulusVerification.VALID,
            newVerifier().verify(decoded.cleartextBase64, decoded.armoredSignature!!),
        )
    }

    @Test
    fun `tampered modulus cleartext fails verification`() {
        val decoded = ProtonModulusEnvelope.decode(MODULUS_ENVELOPE)
        val tampered = decoded.cleartextBase64.replaceFirst('k', 'x')
        assertEquals(
            ProtonModulusVerification.INVALID,
            newVerifier().verify(tampered, decoded.armoredSignature!!),
        )
    }

    @Test
    fun `x derivation matches the live-verified reference`() {
        val modulusLE = Base64.getDecoder().decode(MODULUS_CLEARTEXT_B64)
        val x = SrpXDerivation.deriveX(PASSWORD.toCharArray(), SALT_B64, modulusLE)
        assertEquals(BigInteger(EXPECTED_X_HEX, 16), x)
    }

    @Test
    fun `client proof with fixed secret matches the live-verified reference`() {
        val modulusLE = Base64.getDecoder().decode(MODULUS_CLEARTEXT_B64)
        val n = BigInteger(1, modulusLE.reversedArray())
        val padLen = (n.bitLength() + 7) / 8
        val b = BigInteger(1, Base64.getDecoder().decode(SERVER_EPHEMERAL_B64).reversedArray())
        val x = BigInteger(EXPECTED_X_HEX, 16)

        val proof = SrpClient(random = fixedSecretRandom()).login(N = n, serverEphemeralB = b, x = x)

        assertEquals(BigInteger(EXPECTED_A_HEX, 16), proof.clientEphemeralA)
        assertArrayEquals(EXPECTED_M1_HEX.hexToBytes(), proof.clientProofM1)
        assertEquals(
            EXPECTED_CLIENT_EPHEMERAL_B64,
            Base64.getEncoder().encodeToString(proof.clientEphemeralA.toLittleEndianBytes(padLen)),
        )
        assertEquals(EXPECTED_CLIENT_PROOF_B64, Base64.getEncoder().encodeToString(proof.clientProofM1))
    }

    @Test
    fun `auth request body matches the live-verified wire shape`() {
        val modulusLE = Base64.getDecoder().decode(MODULUS_CLEARTEXT_B64)
        val n = BigInteger(1, modulusLE.reversedArray())
        val padLen = (n.bitLength() + 7) / 8
        val b = BigInteger(1, Base64.getDecoder().decode(SERVER_EPHEMERAL_B64).reversedArray())
        val proof = SrpClient(random = fixedSecretRandom())
            .login(N = n, serverEphemeralB = b, x = BigInteger(EXPECTED_X_HEX, 16))

        val body = json.encodeToString(
            AuthRequest.serializer(),
            AuthRequest(
                username = "golden-user",
                clientEphemeral = Base64.getEncoder()
                    .encodeToString(proof.clientEphemeralA.toLittleEndianBytes(padLen)),
                clientProof = Base64.getEncoder().encodeToString(proof.clientProofM1),
                srpSession = SRP_SESSION,
                payload = emptyMap(),
            ),
        )
        assertEquals(EXPECTED_AUTH_BODY, body)
    }

    private fun newVerifier() = BouncyCastleProtonModulusVerifier(
        pinnedPublicKeyArmored = BouncyCastleProtonModulusVerifier.loadPinnedKeyFromClasspath(),
    )

    /** Deterministic client secret `a`: fills the requested bytes 0x01, 0x02, ... */
    private fun fixedSecretRandom(): SecureRandom = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (i + 1).toByte()
        }
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) {
            ((Character.digit(this[it * 2], 16) shl 4) + Character.digit(this[it * 2 + 1], 16)).toByte()
        }
    }

    private companion object {
        private const val PASSWORD = "DifferentialTest#1"
        private const val SALT_B64 = "2ux0S+euRgGU+A=="
        private const val SRP_SESSION = "61e4d02aa23d9d8786078f2dd5fbadbb"

        private const val SERVER_EPHEMERAL_B64 =
            "37fXSc5rBLiKevxkKo/KRRZiKFdewv6DhveoRvYqgk/Ppb6uMuQPZtxXv+Rv84UnEXKozyq0/Pua9YHeJ3bgEz0ULAokBLR20gmH" +
            "/4/xz40IwEnfRRafOo9BDH7ju6FSV1utixivo6BT1uCONKcJbbCSQ1OTGBC2j8+4dEsrf/h9ktv5wjRauAvmr0lD4kSosgaENuIX" +
            "gi/ZYEZvpC55GbOwGqW+z3iTkiT4GxfF2MVQoartqr2ocqwo0zSIk+Fi3FXWz5Tp+TtJMd0wF7fVDbyyccCavCeHz7yEDoiJAiVA" +
            "rY72O54aL+CAXKKngSreJrF6FXIOBjKPpDV/OclAnw=="

        private const val MODULUS_CLEARTEXT_B64 =
            "k5UFGE957vWcwT9laDPihAKvMdhNRwuN6WGh76Ot4isFtplMkNq2fZBZxkpwVS8BYXhzMJRKsPbyqf95N+teQ6XRUlWWMBK5NbHq" +
            "+gmUc7mvB5E19lc1ckHJfWbYtJaUuN1tRb+RBZDeYatf1iO5M/6AxbDm/UhZ2gHCWVwU4+9q247C0gyc/GUWyRNHS4LDV03GgkKb" +
            "smpPJdJzRzGfx7lU2FVFjbPPjsrd9wxdiOUGl5ikI34m7Mda6CDR6TIY7xUgALXm+9YjH5siwsRKNjd9zeyxf/HPs//OtnAWT3Vw" +
            "q/Pgh0/Q24yce6N39ViYPl/mtylGV0rZypffo59/7A=="

        private const val EXPECTED_X_HEX =
            "e65055c99a10d7c4b7ce5f6bf8ee7b341bcb440ddfef3591997912aa4c44bce30ed0fcac2eab2c893147bf5fb1b0a9516edf" +
            "b18c45d91c6a2d78c0ae456e71e1c909b33c207bf468af455c94a91a8366e3381085562b749678568b9390a78486a30b8ae5" +
            "7a7e6db047a3ae0c3e34f1bd5831057b39912e175101a1167818610975be2f1f98bc19d27d92e3e2588787dd097a4ed66c01" +
            "f9042c28f53dc321f0b64f42b3534743b94091074a5488d74bcb000f4400a076ce225699b612746495c5a8074d8265a47f79" +
            "57d931cb308f73f62a2c3a558315286cc5e44c4188746617ba8e6973395cefb08ec9490fa3320183c154a4e14991e7927151" +
            "548d8d6cb762"

        private const val EXPECTED_A_HEX =
            "9ffa91ca3efdd6944d316038c20a230236e2be0f61972bf7a58a0673107cf11901ded3dd42df2f5871a331d64c1d09608860" +
            "7b2ba9dfccdeb48ddef9fd336d32751239ba04ca136384f36f15b85a27f74a73943470ea0a160cba331354580792a989351e" +
            "ab3c11b8d782909ea21a36dad3ce9c5ec4683bb72623175a2149392394b470404c641d822f81549d9235ef403318d60ac237" +
            "cf1ea8b0df750b5d3a9770f00f6b9a83d18dee3b773ae8ee1effe45887cf315fc105dba096d527d32b08a126f43aaf424127" +
            "4dcdc7a6b0fa796f02b28dd8d2abcef13c081e6b9d1541496e6526521ac30828f3071475b363256ca35becf964222a03157e" +
            "9df1a430122a"

        private const val EXPECTED_M1_HEX =
            "92aebac641888e7c82e85d8c7dc462a9d2b75d706c72b229e2a59380e4d7f7676652732351993d0764cb21511e67fa7bb486" +
            "890e2a1d0152a4b0ee7053177ade47145478a5c71c0e4d12c5c706f4aa4f714a6c0c41f14f4bee555800fa731327567bd3f3" +
            "dd41d6fe3dd785049bcc64270a3b09c99f9d2d8c1c2c4bd2c30ea96d5465ca0e2d3066b65562cec53db97a6edcf1aa9fe0f8" +
            "073b013c4f041f9c721ab35155436bcf7d0cdf931a47e0d870a0c73f26d541b4083d6719aba36d3bc83cc4b1f72f00e60649" +
            "c2cbe7e85103565b28216cb1d33e67663f210a6e6f21ee44801f9ea3e8fce79e1c78fe55d0f735c0d75abe6093c0c72c51b4" +
            "43047c188c41"

        private const val EXPECTED_CLIENT_EPHEMERAL_B64 =
            "KhIwpPGdfhUDKiJk+exbo2wlY7N1FAfzKAjDGlImZW5JQRWdax4IPPHOq9LYjbICb3n6sKbHzU0nQUKvOvQmoQgr0yfVlqDbBcFf" +
            "Mc+HWOT/Hu7oOnc77o3Rg5prD/BwlzpdC3XfsKgezzfCCtYYM0DvNZKdVIEvgh1kTEBwtJQjOUkhWhcjJrc7aMRenM7T2jYaop6Q" +
            "gte4ETyrHjWJqZIHWFQTM7oMFgrqcDSUc0r3J1q4FW/zhGMTygS6ORJ1Mm0z/fnejbTezN+pK3tgiGAJHUzWMaNxWC/fQt3T3gEZ" +
            "8XwQcwaKpfcrl2EPvuI2AiMKwjhgMU2U1v0+ypH6nw=="

        private const val EXPECTED_CLIENT_PROOF_B64 =
            "kq66xkGIjnyC6F2MfcRiqdK3XXBscrIp4qWTgOTX92dmUnMjUZk9B2TLIVEeZ/p7tIaJDiodAVKksO5wUxd63kcUVHilxxwOTRLF" +
            "xwb0qk9xSmwMQfFPS+5VWAD6cxMnVnvT891B1v4914UEm8xkJwo7CcmfnS2MHCxL0sMOqW1UZcoOLTBmtlVizsU9uXpu3PGqn+D4" +
            "BzsBPE8EH5xyGrNRVUNrz30M35MaR+DYcKDHPybVQbQIPWcZq6NtO8g8xLH3LwDmBknCy+foUQNWWyghbLHTPmdmPyEKbm8h7kSA" +
            "H56j6Pznnhx4/lXQ9zXA11q+YJPAxyxRtEMEfBiMQQ=="

        /** Exact JSON the orchestrator must put on the wire for `auth`. */
        private val EXPECTED_AUTH_BODY =
            "{\"Username\":\"golden-user\",\"ClientEphemeral\":\"" + EXPECTED_CLIENT_EPHEMERAL_B64 +
                "\",\"ClientProof\":\"" + EXPECTED_CLIENT_PROOF_B64 +
                "\",\"SRPSession\":\"" + SRP_SESSION + "\",\"Payload\":{}}"

        /** The captured envelope, reassembled exactly as received (LF newlines). */
        private val MODULUS_ENVELOPE = listOf(
            "-----BEGIN PGP SIGNED MESSAGE-----",
            "Hash: SHA256",
            "",
            MODULUS_CLEARTEXT_B64,
            "-----BEGIN PGP SIGNATURE-----",
            "Version: ProtonMail",
            "Comment: https://protonmail.com",
            "",
            "wl4EARYIABAFAlwB1j4JEDUFhcTpUY8mAABWkwEA7qJr6y1w+PAQe8e6BUtv",
            "Zo2W2ZPzgo7LhGMclm9NlY4BAMPw5g/1/yyVqP9Fk01sdY1g+0tK098qGv6+",
            "yQjos9UA",
            "=DV3g",
            "-----END PGP SIGNATURE-----",
        ).joinToString("\n")
    }
}
