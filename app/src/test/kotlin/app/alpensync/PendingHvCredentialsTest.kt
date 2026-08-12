package app.alpensync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the credential-handling contract that three live logins failed on
 * (2026-08-12): the stash and the retry must never share a CharArray, or
 * the security wipe empties the password the retry is about to hash and
 * Proton answers 8002 (PASSWORD_WRONG) against a correct password.
 */
class PendingHvCredentialsTest {

    private val secret = charArrayOf('h', 'u', 'n', 't', 'e', 'r', '2')

    @Test
    fun `take survives a wipe of the stash — the 8002 regression`() {
        val pending = PendingHvCredentials()
        pending.stash("alice", secret)

        val (username, password) = pending.take()!!

        assertEquals("alice", username)
        // Before the fix this array had been zeroed by take()'s own clear().
        assertArrayEquals(secret, password)
    }

    @Test
    fun `the caller's array is not aliased by the stash`() {
        val pending = PendingHvCredentials()
        val caller = secret.copyOf()
        pending.stash("alice", caller)

        // The caller wipes its own copy, as LoginController.login does.
        caller.fill(ZERO)

        assertArrayEquals(secret, pending.take()!!.second)
    }

    @Test
    fun `take empties the stash so credentials are not retained`() {
        val pending = PendingHvCredentials()
        pending.stash("alice", secret)

        pending.take()

        assertNull("a second take must find nothing", pending.take())
    }

    @Test
    fun `take on an empty stash is null, not a crash`() {
        assertNull(PendingHvCredentials().take())
    }

    @Test
    fun `clear empties the stash`() {
        val pending = PendingHvCredentials()
        pending.stash("alice", secret)

        pending.clear()

        assertNull(pending.take())
    }

    @Test
    fun `stash replaces a previous stash`() {
        val pending = PendingHvCredentials()
        pending.stash("alice", secret)
        pending.stash("bob", charArrayOf('x'))

        val (username, password) = pending.take()!!

        assertEquals("bob", username)
        assertArrayEquals(charArrayOf('x'), password)
    }

    private companion object {
        const val ZERO = '\u0000'
    }
}
