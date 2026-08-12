// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.hv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract tests pinning the exact URL shape protoncore's HV3DialogFragment
 * builds (embed/token/methods/theme, URLEncoder form-encoding) so the
 * in-app sheet stays byte-compatible with what the challenge page expects.
 */
class HumanVerificationUrlTest {

    @Test fun `dark theme URL matches protoncore shape`() {
        assertEquals(
            "https://verify.proton.me?embed=true&token=TOK&methods=captcha&theme=1",
            buildHumanVerificationUrl("TOK", listOf("captcha"), darkTheme = true),
        )
    }

    @Test fun `light theme maps to 2`() {
        assertEquals(
            "https://verify.proton.me?embed=true&token=TOK&methods=captcha&theme=2",
            buildHumanVerificationUrl("TOK", listOf("captcha"), darkTheme = false),
        )
    }

    @Test fun `methods join with comma and the comma is form-encoded`() {
        // protoncore URLEncoder-encodes the joined value, so ',' → %2C.
        assertEquals(
            "https://verify.proton.me?embed=true&token=TOK&methods=captcha%2Cemail&theme=1",
            buildHumanVerificationUrl("TOK", listOf("captcha", "email"), darkTheme = true),
        )
    }

    @Test fun `token characters are form-encoded`() {
        assertEquals(
            "https://verify.proton.me?embed=true&token=a+b%2Fc%3D&methods=captcha&theme=2",
            buildHumanVerificationUrl("a b/c=", listOf("captcha"), darkTheme = false),
        )
    }
}
