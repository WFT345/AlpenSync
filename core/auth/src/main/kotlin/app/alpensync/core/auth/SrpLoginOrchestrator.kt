// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/sync/src/main/kotlin/io/pcontacts/core/sync/auth/SrpLoginOrchestrator.kt
// Deviations:
//  - SafeLog instead of pcontacts' Logger (no message parameters at all).
//  - PasswordMode==2 → typed TwoPasswordUnsupported + session wipe (ADR 0004
//    Q2, owner decision: fail loud). pcontacts ignores the field.
//  - Single ProtonApi interface instead of separate auth/users interfaces.
//  - Catch clauses narrowed to checked/serialization types (detekt Rule 7).

package app.alpensync.core.auth

import app.alpensync.core.api.InMemorySession
import app.alpensync.core.api.ProtonApi
import app.alpensync.core.api.dto.AuthRequest
import app.alpensync.core.api.dto.AuthResponse
import app.alpensync.core.api.dto.InfoRequest
import app.alpensync.core.api.dto.InfoResponse
import app.alpensync.core.api.dto.TwoFactorRequest
import app.alpensync.core.api.dto.TwoFactorResponse
import app.alpensync.core.api.http.AppVersionRejectedException
import app.alpensync.core.api.http.EndpointFamily
import app.alpensync.core.api.http.HumanVerificationRequiredException
import app.alpensync.core.api.http.ProtonServerCodeException
import app.alpensync.core.api.http.mapServerCodes
import app.alpensync.core.api.httpStatusCode
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.auth.bcrypt.ComputeKeyPassword
import app.alpensync.core.auth.srp.BouncyCastleProtonModulusVerifier
import app.alpensync.core.auth.srp.ProtonModulusEnvelope
import app.alpensync.core.auth.srp.ProtonModulusVerification
import app.alpensync.core.auth.srp.ProtonModulusVerifier
import app.alpensync.core.auth.srp.SrpClient
import app.alpensync.core.auth.srp.SrpProof
import app.alpensync.core.auth.store.SecretStore
import app.alpensync.core.auth.util.toLittleEndianBytes
import java.io.IOException
import java.math.BigInteger
import java.util.Base64

/**
 * Coordinates one SRP login attempt end-to-end: fetch `auth/info`, verify
 * the modulus envelope against the pinned key (fail-closed), derive `x`,
 * run client-side SRP, post `auth`, validate `ServerProof` BEFORE trusting
 * the session, persist tokens, then derive and store the keyPassword.
 * Signals whether a 2FA challenge is outstanding.
 *
 * Inputs are injectable so unit tests can point [api] at MockWebServer,
 * hand [srp] a seeded SecureRandom, and use InMemorySecretStore /
 * InMemorySession. Production defaults perform every fail-closed check.
 */
class SrpLoginOrchestrator(
    private val api: ProtonApi,
    private val srp: SrpClient,
    private val secretStore: SecretStore,
    private val session: InMemorySession,
    private val serverProofVerifier: (server: ByteArray, expected: ByteArray) -> Boolean =
        srp::verifyServerProof,
    private val modulusVerifier: ProtonModulusVerifier = BouncyCastleProtonModulusVerifier(
        pinnedPublicKeyArmored = BouncyCastleProtonModulusVerifier.loadPinnedKeyFromClasspath(),
    ),
) {

    @Volatile private var lastUsername: String? = null

    /**
     * `auth`'s access token carries limited scope when 2FA is required;
     * full scope lands only after `auth/2fa` succeeds. So when 2FA is
     * required we stash a private copy of the password and finish the
     * keyPassword derivation inside [submitTwoFactorCode]. The stash is
     * zeroed on success, on rejection, and on any new [login] attempt.
     */
    @Volatile private var pendingTwoFactorPassword: CharArray? = null

    private sealed interface Step<out T> {
        data class Ok<T>(val value: T) : Step<T>
        data class Abort(val result: LoginResult) : Step<Nothing>
    }

    private inline fun <T> Step<T>.orReturn(block: (LoginResult) -> Nothing): T = when (this) {
        is Step.Ok -> value
        is Step.Abort -> block(result)
    }

    private data class VerifiedModulus(val n: BigInteger, val nBytesLE: ByteArray, val padLen: Int)

    suspend fun login(username: String, password: CharArray): LoginResult = try {
        loginInternal(username, password)
    } finally {
        password.fill('\u0000')
    }

    // The early returns mirror the SRP phases (info, modulus, proof, auth,
    // 2FA branch, key derivation); collapsing them buries the protocol shape.
    @Suppress("ReturnCount")
    private suspend fun loginInternal(username: String, password: CharArray): LoginResult {
        lastUsername = username
        clearPendingTwoFactorPassword()

        val info = fetchInfo(username).orReturn { return it }
        // SrpXDerivation implements go-srp versions 3/4 only. Any other
        // Version means we compute the wrong proof and the server answers
        // 8002 (PASSWORD_WRONG) with a perfectly correct password — so the
        // Version is logged (non-secret) to make that case diagnosable
        // rather than indistinguishable from a typo. Not a hard fail: the
        // account's real Version has never been observed, and refusing to
        // log in on an unrecognised value would be guessing in the other
        // direction (research notes Section 9).
        SafeLog.log(SafeLog.Event.LOGIN_AUTH_VERSION, info.version)
        val mod = decodeAndVerifyModulus(info).orReturn { return it }
        val proof = computeSrpProof(password, info, mod).orReturn { return it }
        val authResp = submitAuthAndVerifyProof(username, info, proof, mod.padLen).orReturn { return it }

        if (authResp.passwordMode == PASSWORD_MODE_TWO_PASSWORD) {
            // ADR 0004 Q2 (owner decision): fail loud on two-password
            // accounts — the keyPassword derivation assumes login password
            // == mailbox password and would silently produce a wrong key.
            SafeLog.log(SafeLog.Event.LOGIN_TWO_PASSWORD_MODE_UNSUPPORTED)
            wipeSession()
            return LoginResult.TwoPasswordUnsupported(uid = authResp.uid, username = username)
        }

        persistSession(authResp)

        val needsTwoFactor = authResp.twoFactor and TWO_FACTOR_TOTP_BIT != 0
        if (needsTwoFactor) {
            SafeLog.log(SafeLog.Event.LOGIN_TWO_FACTOR_REQUIRED)
            pendingTwoFactorPassword = password.copyOf()
            return LoginResult.TwoFactorRequired(uid = authResp.uid, username = username)
        }

        return finishKeyDerivation(password, authResp.uid, username)
    }

    /**
     * Second stage of a 2FA login. Call after [login] returned
     * [LoginResult.TwoFactorRequired] and the user entered their TOTP code.
     * The 2FA response carries no new tokens — the existing session is
     * elevated server-side (research notes Section 3).
     */
    // The early returns mirror the distinct failure modes of auth/2fa.
    @Suppress("ReturnCount")
    suspend fun submitTwoFactorCode(code: String): LoginResult {
        val uid = session.uid()
        if (uid.isNullOrBlank()) {
            SafeLog.log(SafeLog.Event.TWO_FACTOR_WITHOUT_SESSION)
            return LoginResult.Failed(reason = "no_session")
        }
        val username = lastUsername ?: ""

        val response = callTwoFactor(code, uid, username).orReturn { return it }

        if (response.code != PROTON_SUCCESS_CODE) {
            SafeLog.log(SafeLog.Event.TWO_FACTOR_REJECTED, response.code)
            clearPendingTwoFactorPassword()
            return LoginResult.Failed(reason = "two_factor_rejected", uid = uid, username = username)
        }

        val pending = pendingTwoFactorPassword
        pendingTwoFactorPassword = null
        if (pending == null) {
            return LoginResult.Failed(reason = "unexpected_state", uid = uid, username = username)
        }
        return try {
            finishKeyDerivation(pending, uid, username)
        } finally {
            pending.fill('\u0000')
        }
    }

    private suspend fun callTwoFactor(code: String, uid: String, username: String): Step<TwoFactorResponse> =
        try {
            Step.Ok(mapServerCodes(EndpointFamily.AUTH_2FA) { api.auth2FA(TwoFactorRequest(twoFactorCode = code)) })
        } catch (e: HumanVerificationRequiredException) {
            Step.Abort(
                LoginResult.HumanVerificationRequired(e.verificationToken, e.verificationMethods, uid, username),
            )
        } catch (e: ProtonServerCodeException) {
            // An HTTP error from auth/2fa (e.g. a 422 rejecting the TOTP) is
            // the server saying "wrong code" — same outcome as a
            // 200-with-error-code rejection, and it must never crash.
            SafeLog.log(SafeLog.Event.TWO_FACTOR_REJECTED, e.protonCode ?: e.httpStatus)
            clearPendingTwoFactorPassword()
            Step.Abort(LoginResult.Failed(reason = "two_factor_rejected", uid = uid, username = username))
        } catch (e: IOException) {
            SafeLog.log(SafeLog.Event.TWO_FACTOR_CALL_FAILED, e.httpStatusCode() ?: 0)
            Step.Abort(LoginResult.Failed(reason = "two_factor_failed", uid = uid, username = username))
        }

    private suspend fun fetchInfo(username: String): Step<InfoResponse> = try {
        Step.Ok(mapServerCodes(EndpointFamily.AUTH_INFO) { api.getInfo(InfoRequest(username = username)) })
    } catch (e: HumanVerificationRequiredException) {
        // 9001 on auth/info — the pre-session captcha path on fresh IPs.
        Step.Abort(
            LoginResult.HumanVerificationRequired(
                verificationToken = e.verificationToken,
                verificationMethods = e.verificationMethods,
            ),
        )
    } catch (ignored: AppVersionRejectedException) {
        Step.Abort(LoginResult.Failed(reason = "appversion_rejected"))
    } catch (e: ProtonServerCodeException) {
        // Unmapped Code (live test 1's 422): surface it honestly, never guess.
        Step.Abort(LoginResult.ServerError(e.protonCode, e.endpointFamily))
    } catch (e: IOException) {
        SafeLog.log(SafeLog.Event.LOGIN_INFO_FAILED, e.httpStatusCode() ?: 0)
        Step.Abort(LoginResult.Failed(reason = "info_failed"))
    } catch (ignored: IllegalArgumentException) {
        SafeLog.log(SafeLog.Event.LOGIN_INFO_FAILED)
        Step.Abort(LoginResult.Failed(reason = "info_failed"))
    }

    private fun decodeAndVerifyModulus(info: InfoResponse): Step<VerifiedModulus> {
        val decoded = ProtonModulusEnvelope.decode(info.modulus)
        val armoredSig = decoded.armoredSignature
            ?: run {
                SafeLog.log(SafeLog.Event.LOGIN_MODULUS_UNSIGNED)
                return Step.Abort(LoginResult.Failed(reason = "modulus_unsigned"))
            }

        when (modulusVerifier.verify(decoded.cleartextBase64, armoredSig)) {
            ProtonModulusVerification.VALID -> Unit
            ProtonModulusVerification.INVALID -> {
                SafeLog.log(SafeLog.Event.LOGIN_MODULUS_SIGNATURE_INVALID)
                return Step.Abort(LoginResult.Failed(reason = "modulus_signature_invalid"))
            }
            ProtonModulusVerification.NO_SIGNER_KEY -> {
                SafeLog.log(SafeLog.Event.LOGIN_MODULUS_PIN_MISSING)
                return Step.Abort(LoginResult.Failed(reason = "modulus_pin_missing"))
            }
        }

        // Proton's API sends BigInteger values little-endian on the wire;
        // reverse before constructing BigIntegers; raw LE bytes feed
        // hashPassword unchanged.
        val nBytesLE = Base64.getDecoder().decode(decoded.cleartextBase64)
        val n = BigInteger(1, nBytesLE.reversedArray())
        return Step.Ok(VerifiedModulus(n = n, nBytesLE = nBytesLE, padLen = (n.bitLength() + 7) / 8))
    }

    private fun computeSrpProof(
        password: CharArray,
        info: InfoResponse,
        mod: VerifiedModulus,
    ): Step<SrpProof> {
        val x = SrpXDerivation.deriveX(password, info.salt, mod.nBytesLE)
        val bBytesLE = Base64.getDecoder().decode(info.serverEphemeral)
        val b = BigInteger(1, bBytesLE.reversedArray())

        return try {
            Step.Ok(srp.login(N = mod.n, serverEphemeralB = b, x = x))
        } catch (ignored: IllegalArgumentException) {
            SafeLog.log(SafeLog.Event.LOGIN_SRP_COMPUTATION_FAILED)
            Step.Abort(LoginResult.Failed(reason = "srp_failed"))
        }
    }

    private suspend fun submitAuthAndVerifyProof(
        username: String,
        info: InfoResponse,
        proof: SrpProof,
        padLen: Int,
    ): Step<AuthResponse> {
        val authResp = try {
            mapServerCodes(EndpointFamily.AUTH) {
                api.auth(
                    AuthRequest(
                        username = username,
                        clientEphemeral = Base64.getEncoder()
                            .encodeToString(proof.clientEphemeralA.toLittleEndianBytes(padLen)),
                        clientProof = Base64.getEncoder().encodeToString(proof.clientProofM1),
                        srpSession = info.srpSession,
                        payload = emptyMap(), // live-verified accepted (2026-05-24)
                    ),
                )
            }
        } catch (e: HumanVerificationRequiredException) {
            return Step.Abort(
                LoginResult.HumanVerificationRequired(
                    verificationToken = e.verificationToken,
                    verificationMethods = e.verificationMethods,
                ),
            )
        } catch (ignored: AppVersionRejectedException) {
            return Step.Abort(LoginResult.Failed(reason = "appversion_rejected"))
        } catch (e: ProtonServerCodeException) {
            // Unmapped Code (live test 1's post-captcha 422) — surface it honestly.
            return Step.Abort(LoginResult.ServerError(e.protonCode, e.endpointFamily))
        } catch (e: IOException) {
            SafeLog.log(SafeLog.Event.LOGIN_AUTH_CALL_FAILED, e.httpStatusCode() ?: 0)
            return Step.Abort(LoginResult.Failed(reason = "auth_failed"))
        } catch (ignored: IllegalArgumentException) {
            SafeLog.log(SafeLog.Event.LOGIN_AUTH_CALL_FAILED)
            return Step.Abort(LoginResult.Failed(reason = "auth_failed"))
        }

        return verifyServerProof(authResp, proof)
    }

    private fun verifyServerProof(authResp: AuthResponse, proof: SrpProof): Step<AuthResponse> {
        val serverProof = try {
            Base64.getDecoder().decode(authResp.serverProof)
        } catch (ignored: IllegalArgumentException) {
            SafeLog.log(SafeLog.Event.LOGIN_SERVER_PROOF_DECODE_FAILED)
            return Step.Abort(LoginResult.Failed(reason = "server_proof_decode_failed", uid = authResp.uid))
        }

        if (!serverProofVerifier(serverProof, proof.expectedServerProofM2)) {
            // Possible MITM — never trust the session.
            SafeLog.log(SafeLog.Event.LOGIN_SERVER_PROOF_MISMATCH)
            return Step.Abort(LoginResult.Failed(reason = "server_proof_mismatch", uid = authResp.uid))
        }
        return Step.Ok(authResp)
    }

    private fun persistSession(authResp: AuthResponse) {
        secretStore.setUid(authResp.uid)
        secretStore.setAccessToken(authResp.accessToken)
        secretStore.setRefreshToken(authResp.refreshToken)
        session.update(uid = authResp.uid, accessToken = authResp.accessToken)
    }

    private fun wipeSession() {
        secretStore.setUid(null)
        secretStore.setAccessToken(null)
        secretStore.setRefreshToken(null)
        session.clear()
    }

    /**
     * Runs `/users` + `/keys/salts`, derives
     * `keyPassword = bcrypt(password, primaryKeySalt)[29:]`, and persists
     * it. On non-HV failure the half-written session tokens are cleared so
     * a later sync can't run with tokens but no key material.
     */
    private suspend fun finishKeyDerivation(
        password: CharArray,
        uid: String,
        username: String,
    ): LoginResult = try {
        deriveAndPersistKeyPassword(password)
        LoginResult.Success(uid = uid, username = username)
    } catch (e: HumanVerificationRequiredException) {
        LoginResult.HumanVerificationRequired(e.verificationToken, e.verificationMethods, uid, username)
    } catch (e: ProtonServerCodeException) {
        wipeSession()
        LoginResult.ServerError(e.protonCode, e.endpointFamily, uid, username)
    } catch (e: IOException) {
        SafeLog.log(SafeLog.Event.LOGIN_KEY_DERIVATION_FAILED, e.httpStatusCode() ?: 0)
        wipeSession()
        LoginResult.Failed(reason = "key_derivation_failed", uid = uid, username = username)
    } catch (ignored: IllegalStateException) {
        SafeLog.log(SafeLog.Event.LOGIN_KEY_DERIVATION_FAILED)
        wipeSession()
        LoginResult.Failed(reason = "key_derivation_failed", uid = uid, username = username)
    }

    private suspend fun deriveAndPersistKeyPassword(password: CharArray) {
        val user = mapServerCodes(EndpointFamily.USERS) { api.getUser() }.user
        val primary = user.keys.firstOrNull { it.primary == 1 && it.active == 1 }
            ?: error("no active primary key in /users")

        val saltDto = mapServerCodes(EndpointFamily.KEYS_SALTS) { api.getKeySalts() }.keySalts
            .firstOrNull { it.keyId == primary.id }
            ?: error("no /keys/salts entry for the primary key")
        val saltB64 = saltDto.keySalt
            ?: error("primary key has null KeySalt — key activation pending")

        val keyPassword = ComputeKeyPassword.derive(password, saltB64)
        secretStore.setKeyPassword(keyPassword.toByteArray(Charsets.UTF_8))
    }

    private fun clearPendingTwoFactorPassword() {
        pendingTwoFactorPassword?.fill('\u0000')
        pendingTwoFactorPassword = null
    }

    private companion object {
        const val TWO_FACTOR_TOTP_BIT = 1
        const val PROTON_SUCCESS_CODE = 1000
        const val PASSWORD_MODE_TWO_PASSWORD = 2
    }
}
