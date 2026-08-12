// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.api.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Offline contract tests for the Code 9001/12087 detection and the
 * `Details` extraction (HumanVerificationToken + HumanVerificationMethods,
 * field names per protoncore's BaseRetrofitApi.kt:61-64). The parser must
 * fail closed: malformed input degrades to nulls, never to a crash, and
 * never suppresses a detected 9001.
 */
class HumanVerificationInterceptorTest {

    @Test fun `9001 with full Details extracts token and methods`() {
        val body = """
            {"Code": 9001, "Error": "Human verification required",
             "Details": {"HumanVerificationMethods": ["captcha", "email"],
                         "HumanVerificationToken": "start-token-abc"}}
        """.trimIndent()
        val parsed = HumanVerificationInterceptor.parseHvCode(body)
        assertEquals(9001, parsed?.code)
        assertEquals("start-token-abc", parsed?.token)
        assertEquals(listOf("captcha", "email"), parsed?.methods)
    }

    @Test fun `9001 without Details still detected with null fields`() {
        val parsed = HumanVerificationInterceptor.parseHvCode("""{"Code": 9001}""")
        assertEquals(9001, parsed?.code)
        assertNull(parsed?.token)
        assertNull(parsed?.methods)
    }

    @Test fun `9001 with non-object Details degrades to nulls`() {
        val parsed = HumanVerificationInterceptor.parseHvCode(
            """{"Code": 9001, "Details": "not-an-object"}""",
        )
        assertEquals(9001, parsed?.code)
        assertNull(parsed?.token)
        assertNull(parsed?.methods)
    }

    @Test fun `malformed methods do not cost the token`() {
        val parsed = HumanVerificationInterceptor.parseHvCode(
            """{"Code": 9001, "Details": {"HumanVerificationMethods": "captcha",
                "HumanVerificationToken": "tok"}}""",
        )
        assertEquals("tok", parsed?.token)
        assertNull(parsed?.methods)
    }

    @Test fun `blank token and empty methods collapse to null`() {
        val parsed = HumanVerificationInterceptor.parseHvCode(
            """{"Code": 9001, "Details": {"HumanVerificationMethods": [],
                "HumanVerificationToken": "  "}}""",
        )
        assertNull(parsed?.token)
        assertNull(parsed?.methods)
    }

    @Test fun `non-string method entries are dropped`() {
        val parsed = HumanVerificationInterceptor.parseHvCode(
            """{"Code": 9001, "Details": {"HumanVerificationMethods": ["captcha", 42],
                "HumanVerificationToken": "tok"}}""",
        )
        assertEquals(listOf("captcha"), parsed?.methods)
    }

    @Test fun `malformed JSON is not a challenge`() {
        assertNull(HumanVerificationInterceptor.parseHvCode("""{"Code": 9001, """))
        assertNull(HumanVerificationInterceptor.parseHvCode("not json at all"))
    }

    @Test fun `other codes are not challenges`() {
        assertNull(HumanVerificationInterceptor.parseHvCode("""{"Code": 1000}"""))
    }

    @Test fun `12087 detected with null Details`() {
        val parsed = HumanVerificationInterceptor.parseHvCode(
            """{"Code": 12087, "Details": {"HumanVerificationToken": "stale"}}""",
        )
        assertEquals(12087, parsed?.code)
        assertNull(parsed?.token)
        assertNull(parsed?.methods)
    }

    @Test fun `exception carries the extracted Details`() {
        val e = HumanVerificationRequiredException(
            verificationToken = "tok",
            verificationMethods = listOf("captcha"),
        )
        assertEquals("tok", e.verificationToken)
        assertEquals(listOf("captcha"), e.verificationMethods)
        // Default construction (stale-token path) carries nulls.
        val stale = HumanVerificationRequiredException()
        assertNull(stale.verificationToken)
        assertNull(stale.verificationMethods)
    }
}
