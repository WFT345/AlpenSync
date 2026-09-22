// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.auth.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collision-proof account-id namespacing (2026-09 audit, finding L2). The
 * old filter-out-unsafe-characters sanitize mapped distinct account ids to
 * the same prefs file and Keystore KEK alias ("a@b.c" and "abc" both
 * became "abc"), so two accounts could silently share — and overwrite —
 * one token store. The digest encoding must make that impossible while
 * staying safe for filenames and Keystore aliases.
 */
class AccountStorageKeyTest {

    @Test
    fun `ids differing only in stripped characters no longer collide`() {
        // The exact collision pair from the audit finding.
        assertNotEquals(AccountStorageKey.of("a@b.c"), AccountStorageKey.of("abc"))
    }

    @Test
    fun `distinct ids produce distinct keys`() {
        assertNotEquals(AccountStorageKey.of("alice@proton.me"), AccountStorageKey.of("bob@proton.me"))
    }

    @Test
    fun `deterministic for the same id`() {
        assertEquals(AccountStorageKey.of("alice@proton.me"), AccountStorageKey.of("alice@proton.me"))
    }

    @Test
    fun `output is fixed-length lowercase hex, safe for filenames and aliases`() {
        // Fixed length also caps the prefs filename regardless of how long
        // the account id is (the old scheme grew with the id).
        val key = AccountStorageKey.of("a".repeat(500) + "@example.test")
        assertTrue("64 lowercase hex chars, was '$key'", Regex("^[0-9a-f]{64}$").matches(key))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank id is rejected`() {
        AccountStorageKey.of("   ")
    }
}
