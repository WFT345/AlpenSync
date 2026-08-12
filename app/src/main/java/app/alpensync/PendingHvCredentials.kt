package app.alpensync

/**
 * Credentials held across an in-app human-verification challenge, so a
 * solved challenge can retry the login the user already submitted without
 * re-asking for the password.
 *
 * The whole point of this type is that the stash and the retry NEVER share
 * a `CharArray`: [stash] copies in, [take] copies out. They shared one once,
 * and it cost three live logins — [clear] zeroed the very array the retry
 * was about to hash, so every post-challenge attempt computed its SRP proof
 * over NUL characters and Proton answered 8002 (PASSWORD_WRONG) against a
 * perfectly correct password (live tests 1-3, 2026-08-12; see
 * docs/research/m1-auth-api-notes.md). Aliasing a buffer that something else
 * wipes for security is invisible at the call site, so the copies live here
 * where they cannot be forgotten.
 *
 * Zeroing discipline is unchanged (plan Rule 1): the stash is overwritten on
 * every exit path, and callers still wipe the copy they receive.
 */
internal class PendingHvCredentials {

    // @Volatile: stashed on the main thread, read after a Dispatchers.IO hop.
    @Volatile private var username: String? = null
    @Volatile private var password: CharArray? = null

    /** Replaces any previous stash (zeroing it) with a private copy. */
    fun stash(username: String, password: CharArray) {
        clear()
        this.username = username
        this.password = password.copyOf()
    }

    /**
     * The stashed credentials as a caller-owned copy, wiping the stash in the
     * same step. Null when nothing is stashed (the challenge interrupted the
     * 2FA call, where the code cannot be replayed).
     */
    fun take(): Pair<String, CharArray>? {
        val stashedUsername = username
        val stashedPassword = password?.copyOf()
        clear()
        if (stashedUsername == null || stashedPassword == null) {
            stashedPassword?.fill(NUL)
            return null
        }
        return stashedUsername to stashedPassword
    }

    /** Zeroes and drops the stash. Safe to call when already empty. */
    fun clear() {
        password?.fill(NUL)
        password = null
        username = null
    }

    private companion object {
        const val NUL = '\u0000'
    }
}
