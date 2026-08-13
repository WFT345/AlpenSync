// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.api.log

import android.util.Log

/**
 * The only logging surface allowed inside `:core:*` modules (detekt bans
 * `android.util.Log` imports and `printStackTrace` there; plan Rule 1).
 *
 * SafeLog can emit ONLY fixed, non-secret events. There is deliberately no
 * `String` message parameter — credentials, tokens, and key material can
 * never flow through this API because there is no parameter that could
 * carry them. The only payload is an optional non-secret [Int] detail
 * (an HTTP status, a Proton error `Code`, a retry attempt number).
 *
 * The default destination forwards to [Log] under the fixed [TAG] so a
 * failure on a real phone is diagnosable via logcat (changed after live
 * test 1: an invisible default sink made a server error undiagnosable).
 * Events are non-secret by construction, so release builds log the same
 * fixed events; tests may replace [sink] to capture them.
 */
object SafeLog {

    /** Fixed log events. Every member is a complete, non-secret message. */
    enum class Event {
        // auth flow (:core:auth)
        LOGIN_INFO_FAILED,
        LOGIN_MODULUS_UNSIGNED,
        LOGIN_MODULUS_SIGNATURE_INVALID,
        LOGIN_MODULUS_PIN_MISSING,
        LOGIN_SRP_COMPUTATION_FAILED,
        LOGIN_AUTH_CALL_FAILED,
        LOGIN_SERVER_PROOF_DECODE_FAILED,
        LOGIN_SERVER_PROOF_MISMATCH,
        /**
         * detail = `auth/info`'s `Version` — which go-srp password-hashing
         * algorithm the account uses. We always compute the version-3/4
         * construction; anything else silently yields a wrong proof and an
         * 8002. Logged so a login failure is diagnosable (research notes
         * Section 9 listed this check and it was never wired).
         */
        LOGIN_AUTH_VERSION,
        LOGIN_TWO_FACTOR_REQUIRED,
        LOGIN_TWO_PASSWORD_MODE_UNSUPPORTED,
        LOGIN_KEY_DERIVATION_FAILED,
        TWO_FACTOR_CALL_FAILED,
        TWO_FACTOR_REJECTED,
        TWO_FACTOR_WITHOUT_SESSION,
        // HTTP layer (:core:api)
        RATE_LIMITED_RETRYING,
        RATE_LIMITED_GIVING_UP,
        HUMAN_VERIFICATION_REQUIRED,
        HUMAN_VERIFICATION_STALE_TOKEN_CLEARED,
        /** The rejected-token replay (Code 12087 recovery) was issued. */
        HUMAN_VERIFICATION_TOKEN_REPLAYED,
        /**
         * Emitted by `:app` when the user finished the in-app challenge and
         * the token was stored — the timeline marker that separates "the
         * challenge was solved" from "the retry then failed".
         */
        HUMAN_VERIFICATION_COMPLETED,
        APP_VERSION_REJECTED,
        TOKEN_REFRESH_FAILED,
        TOKEN_REFRESH_INVALID_GRANT,
        RESPONSE_BODY_UNREADABLE,
        // unmapped server codes at call boundaries (mapServerCodes)
        /** detail = the Proton `Code` int (not a secret). */
        SERVER_CODE,
        /** Body had no parseable Code; detail = the HTTP status. */
        SERVER_CODE_STAGE,
        // key unlock (:core:keys)
        KEY_UNLOCK_PRIMARY_FAILED,
        KEY_UNLOCK_USER_KEY_SKIPPED,
        KEY_UNLOCK_ADDRESS_KEY_SKIPPED,
        KEYRING_UNLOCKED,
        // contacts pipeline (:module-contacts; detail = per-contact count where noted)
        CONTACT_CARD_DECRYPT_FAILED,
        CONTACT_CARD_SKIPPED,
        CONTACT_VCARD_FRAGMENT_MALFORMED,
        CONTACT_CARDS_UNVERIFIED,
        // M3a write path (:module-contacts)
        /** An IM handle without a serializable URI scheme was dropped on write. */
        CONTACT_WRITE_IMPP_SKIPPED,
        /** The encrypted canonical store failed to unwrap a row (key invalidated or corruption). */
        CANONICAL_STORE_UNWRAP_FAILED,
        // M3b two-way sync (:module-contacts)
        /** A stored canonical vCard unwrapped but failed to parse — treated as missing, row dropped. */
        CANONICAL_STORE_PARSE_FAILED,
        /** detail = entries enqueued this run from the dirty scan. */
        SYNC_OUTBOX_ENQUEUED,
        /** A push failed retryably; detail = the new attempts count. */
        SYNC_OUTBOX_PUSH_RETRY,
        /** A push failed permanently; the outbox row is quarantined for user requeue. */
        SYNC_OUTBOX_QUARANTINED,
        /** A bulk-delete per-ID sub-response reported failure; detail = the Proton sub-Code. */
        SYNC_OUTBOX_DELETE_SUBCODE_FAILED,
        /** A both-sides field conflict resolved server-wins; detail = conflicting field count. */
        SYNC_CONFLICT_SERVER_WON,
        /** A pushed delete succeeded but the provider's DELETED=1 row could not be purged (the detector re-purges). */
        SYNC_PROVIDER_ROW_PURGE_FAILED,
        // M2d sync runs (:module-contacts; detail on SYNC_GUARD_ABORTED = pending deletions)
        SYNC_SKIPPED_NO_SESSION,
        SYNC_GUARD_ABORTED,
        SYNC_ACCOUNT_SETTINGS_FAILED,
        /** The framework cancelled the run (it interrupts the sync thread). */
        SYNC_CANCELLED,
        /**
         * Last-resort containment in the sync adapter: a failure outside the
         * error taxonomy. Uncaught, it kills the process — which is exactly
         * what an InterruptedException did on the post-login initial sync.
         */
        SYNC_UNEXPECTED_ERROR,
    }

    /** Detail-free event. */
    fun log(event: Event) = emit(event, null)

    /** Event with a non-secret numeric detail (HTTP status, Proton Code, attempt). */
    fun log(event: Event, detail: Int) = emit(event, detail)

    private fun emit(event: Event, detail: Int?) {
        val active = sink
        // Emission must never take the caller down: the Log default throws
        // "not mocked" in local unit tests; a broken sink is dropped too.
        runCatching { active(event, detail) }
    }

    /**
     * Injectable destination, defaulting to logcat (see the class KDoc).
     * Receives only the event + optional numeric detail, so no secret can
     * ever reach a log line.
     */
    @Volatile
    var sink: (Event, Int?) -> Unit = { event, detail -> logcat(event, detail) }

    private fun logcat(event: Event, detail: Int?) {
        if (detail != null) Log.d(TAG, "${event.name} detail=$detail") else Log.d(TAG, event.name)
    }

    private const val TAG = "AlpenSync"
}
