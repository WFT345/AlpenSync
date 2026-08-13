package app.alpensync.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomePrefsTest {

    @Test
    fun first_open_shows_welcome() {
        assertTrue(shouldShowWelcome(seen = false, hasRelinkNotice = false))
    }

    @Test
    fun after_continue_welcome_is_gone() {
        assertFalse(shouldShowWelcome(seen = true, hasRelinkNotice = false))
    }

    @Test
    fun relink_skips_welcome() {
        assertFalse(shouldShowWelcome(seen = false, hasRelinkNotice = true))
    }
}
