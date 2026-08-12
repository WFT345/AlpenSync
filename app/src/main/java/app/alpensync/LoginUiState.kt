package app.alpensync

/**
 * UI states of the M1 debug login screen, in flow order:
 * LoggedOut → LoggingIn → (NeedsTotp, only when the server asks) → LoggedIn,
 * with Error reachable from every step. This screen is the M1 acceptance
 * harness (plan Section 6): it proves SRP login, token persistence, the
 * address list, and one real decryption against a throwaway account entered
 * at runtime. It is NOT the final UX.
 */
sealed interface LoginUiState {

    /** [notice] explains why the user landed back here (session loss). */
    data class LoggedOut(val notice: LogoutNotice? = null) : LoginUiState

    data object LoggingIn : LoginUiState

    /** The server asked for a TOTP code (LoginResult.TwoFactorRequired). */
    data object NeedsTotp : LoginUiState

    /**
     * Code 9001 arrived with a usable `Details` block: the in-app
     * verify.proton.me challenge sheet is shown (ADR 0004 Q3).
     * [startToken] is the server-issued `Details.HumanVerificationToken`,
     * [methods] the offered challenge types (e.g. `["captcha"]`).
     */
    data class HumanVerification(
        val startToken: String,
        val methods: List<String>,
    ) : LoginUiState

    /**
     * [snapshot] is null while addresses are fetched and the keyring is
     * unlocked; [restoredSession] marks the relaunch-with-persisted-session
     * case (the M1 token-persistence proof), where no username is known.
     */
    data class LoggedIn(
        val username: String?,
        val restoredSession: Boolean,
        val snapshot: AccountSnapshot? = null,
    ) : LoginUiState

    /**
     * [kind] selects the honest fixed message (see LoginMessages); [detail]
     * carries optional non-secret context (a failure reason — never
     * credentials, tokens, or key material). [fromAccountLoad]
     * distinguishes login failures (Back to LoggedOut) from
     * address/decrypt failures on a live session (Retry / Log out).
     */
    data class Error(
        val kind: LoginErrorKind,
        val detail: String? = null,
        val fromAccountLoad: Boolean = false,
    ) : LoginUiState
}

/** Why the screen returned to LoggedOut without the user pressing logout. */
enum class LogoutNotice {
    /** Refresh token rejected server-side; the core wiped the session. */
    SESSION_EXPIRED,

    /** Persisted tokens existed without key material; wiped, start over. */
    SESSION_INCOMPLETE,
}

/** One address row on the debug screen. */
data class AddressLine(
    val email: String,
    val send: Boolean,
    val receive: Boolean,
    val keyCount: Int,
)

/** Result of the post-login fetch + keyring unlock + Token decryption. */
data class AccountSnapshot(
    val addresses: List<AddressLine>,
    val decryptionKeyCount: Int,
    val tokenProof: TokenProof,
)

/**
 * The M1 "one decrypted blob" proof. The decrypted Token bytes themselves
 * are never kept or displayed (Rule 1) — only their length reaches the UI.
 */
sealed interface TokenProof {
    data class Decrypted(val plaintextBytes: Int) : TokenProof
    data object NoTokenKey : TokenProof
    data class Failed(val exceptionName: String) : TokenProof
}
