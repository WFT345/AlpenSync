// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/sync/.../contacts/decrypt/ContactDecryptBootstrap.kt (unlock fan-out only).
// Deviations:
//  - standalone :core:keys module (pcontacts keeps this in :core:sync);
//    the contact-processing pipeline around the key set is M2 scope.
//  - SafeLog instead of pcontacts' Logger; no key ids are logged.
//  - the legacy no-Token address-key fallback is kept but flagged
//    UNVERIFIED (research notes Section 5.3).

package app.alpensync.core.keys

import app.alpensync.core.api.dto.AddressDto
import app.alpensync.core.api.dto.GetAddressesResponse
import app.alpensync.core.api.dto.GetKeySaltsResponse
import app.alpensync.core.api.dto.GetUserResponse
import app.alpensync.core.api.log.SafeLog
import app.alpensync.core.auth.bcrypt.ComputeKeyPassword
import app.alpensync.core.auth.openpgp.PgpPrivateKeyHandle
import app.alpensync.core.auth.openpgp.PgpPublicKeyHandle

class KeyringUnlockException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The full set of unlocked key material for one account, scoped to one
 * login/sync operation (ADR 0004 Section 7: memory-only, references
 * dropped at scope exit).
 *
 *  - [primary] — the user key with Primary==1, Active==1; also the
 *    recipient that decrypts address-key Token blobs.
 *  - [decryptionKeys] — every unlockable active user-key subkey and every
 *    unlockable active address key (contacts can be encrypted to either).
 *  - [verificationKeys] — the matching public keys.
 */
data class UnlockedKeySet(
    val primary: UnlockedKey,
    val decryptionKeys: List<PgpPrivateKeyHandle>,
    val verificationKeys: List<PgpPublicKeyHandle>,
)

/**
 * Key-unlock orchestration per ADR 0004 Section 6:
 *   users + keys/salts → keyPassword → unlock ALL secret keys in the user
 *   keyring (primary + encryption subkey) → address keys via their armored
 *   Token decrypted under the user key.
 *
 * Secrets hygiene: passphrases and token bytes are CharArray/ByteArray
 * only and are zeroed after each step; unlocked key material leaves this
 * class only as BC handles held in memory.
 */
object KeyringUnlocker {

    /**
     * Derives the mailbox keyPassword from the account password and the
     * primary key's salt, exactly as the web client's `computeKeyPassword`
     * does. The returned bytes are the UTF-8 encoding of the 31-char
     * bcrypt trailing hash; the caller persists them (Keystore-wrapped)
     * and zeroes its own reference.
     *
     * @throws KeyringUnlockException when the user has no active primary
     *         key or no salt entry for it.
     */
    fun deriveKeyPassword(
        password: CharArray,
        user: GetUserResponse,
        salts: GetKeySaltsResponse,
    ): ByteArray {
        val primaryDto = user.user.keys.firstOrNull { it.primary == 1 && it.active == 1 }
            ?: throw KeyringUnlockException("no active primary key in /users")
        val saltB64 = salts.keySalts.firstOrNull { it.keyId == primaryDto.id }?.keySalt
            ?: throw KeyringUnlockException("no KeySalt for the primary key (activation pending?)")
        return ComputeKeyPassword.derive(password, saltB64).toByteArray(Charsets.UTF_8)
    }

    /**
     * Unlocks every active user key ring with [keyPasswordBytes] (the
     * single keyPassword is valid for all user keys on PasswordMode-1
     * accounts — the same approach pcontacts live-verified), then every
     * active address key via its Token. User keys that fail to unlock
     * abort (wrong keyPassword ≈ stale session → re-login); address keys
     * that fail are skipped, matching WebClients' getDecryptedAddressKeys
     * policy.
     */
    fun unlockAll(
        keyPasswordBytes: ByteArray,
        user: GetUserResponse,
        addresses: GetAddressesResponse,
    ): UnlockedKeySet {
        try {
            val activeUserKeys = user.user.keys.filter { it.active == 1 }
            val primaryDto = activeUserKeys.firstOrNull { it.primary == 1 }
                ?: throw KeyringUnlockException("no active primary key in /users")

            val primaryUnlocked = unlockUserKey(primaryDto.privateKey, keyPasswordBytes, fatal = true)
                ?: throw KeyringUnlockException("primary user key failed to unlock")

            val otherUnlocked = activeUserKeys
                .filter { it.id != primaryDto.id }
                .mapNotNull { unlockUserKey(it.privateKey, keyPasswordBytes, fatal = true) }

            val addressUnlocked = addresses.addresses
                .flatMap(AddressDto::keys)
                .filter { it.active == 1 }
                .mapNotNull { unlockAddressKey(it.privateKey, it.token, primaryUnlocked, keyPasswordBytes) }

            val allUser = listOf(primaryUnlocked) + otherUnlocked
            SafeLog.log(SafeLog.Event.KEYRING_UNLOCKED, allUser.size + addressUnlocked.size)
            return UnlockedKeySet(
                primary = primaryUnlocked,
                decryptionKeys = allUser.flatMap { it.allPrivateKeys } +
                    addressUnlocked.flatMap { it.allPrivateKeys },
                verificationKeys = allUser.map { it.public } + addressUnlocked.map { it.public },
            )
        } finally {
            keyPasswordBytes.fill(0)
        }
    }

    /**
     * @param fatal when true a failure is logged as primary-class (the
     *        caller decides to abort); address-key calls pass false.
     */
    private fun unlockUserKey(armored: String, keyPasswordBytes: ByteArray, fatal: Boolean): UnlockedKey? {
        val passphrase = String(keyPasswordBytes, Charsets.UTF_8).toCharArray()
        return try {
            KeyUnlock.unlock(armored, passphrase)
        } catch (e: KeyUnlockException) {
            SafeLog.log(SafeLog.Event.KEY_UNLOCK_USER_KEY_SKIPPED)
            if (fatal) throw KeyringUnlockException("user key failed to unlock", e) else null
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun unlockAddressKey(
        armored: String,
        token: String?,
        primaryUnlocked: UnlockedKey,
        userKeyPasswordBytes: ByteArray,
    ): UnlockedKey? = if (token == null) {
        // UNVERIFIED: legacy v1 address keys carry no Token and unlock
        // with the user keyPassword directly (WebClients hasMigratedKeys
        // == false branch). Never observed against a live legacy account
        // (docs/research/m1-auth-api-notes.md Section 5.3) — kept so such
        // an account degrades gracefully instead of crashing.
        unlockUserKey(armored, userKeyPasswordBytes, fatal = false)
    } else {
        unlockViaToken(armored, token, primaryUnlocked)
    }

    private fun unlockViaToken(armored: String, token: String, primaryUnlocked: UnlockedKey): UnlockedKey? {
        val tokenPlaintext = try {
            TokenDecryptor.decrypt(token, primaryUnlocked.allPrivateKeys)
        } catch (ignored: TokenDecryptException) {
            SafeLog.log(SafeLog.Event.KEY_UNLOCK_ADDRESS_KEY_SKIPPED)
            return null
        }

        // UNVERIFIED: token charset assumed US-ASCII (Proton ships
        // hex-encoded random bytes); a future binary token would fail
        // here and the key would be skipped (research notes Section 5.3).
        val tokenChars = String(tokenPlaintext, Charsets.US_ASCII).toCharArray()
        return try {
            KeyUnlock.unlock(armored, tokenChars)
        } catch (ignored: KeyUnlockException) {
            SafeLog.log(SafeLog.Event.KEY_UNLOCK_ADDRESS_KEY_SKIPPED)
            null
        } finally {
            tokenChars.fill(' ')
            tokenPlaintext.fill(0)
        }
    }
}
