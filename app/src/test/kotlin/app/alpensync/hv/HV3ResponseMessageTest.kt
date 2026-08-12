// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.hv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for the JS-bridge message parser, mirroring the shapes
 * protoncore's HV3ResponseMessage defines (success / notification /
 * resize / loaded, payload type+text+token+height). Fail-closed: unknown
 * types, missing fields, and garbage parse to null or field-less messages
 * and must never throw.
 */
class HV3ResponseMessageTest {

    @Test fun `success message yields token and type`() {
        val msg = parseHV3ResponseMessage(
            """{"type":"HUMAN_VERIFICATION_SUCCESS",
                "payload":{"token":"hv-token","type":"captcha"}}""",
        )
        assertEquals(HV3ResponseMessage.Type.SUCCESS, msg?.type)
        assertEquals("hv-token" to "captcha", msg?.successToken())
    }

    @Test fun `success without token yields no credentials`() {
        val msg = parseHV3ResponseMessage("""{"type":"HUMAN_VERIFICATION_SUCCESS"}""")
        assertEquals(HV3ResponseMessage.Type.SUCCESS, msg?.type)
        assertNull(msg?.successToken())
    }

    @Test fun `success with blank token yields no credentials`() {
        val msg = parseHV3ResponseMessage(
            """{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"token":" ","type":"captcha"}}""",
        )
        assertNull(msg?.successToken())
    }

    @Test fun `notification carries text and message type`() {
        val msg = parseHV3ResponseMessage(
            """{"type":"NOTIFICATION","payload":{"type":"error","text":"try again"}}""",
        )
        assertEquals(HV3ResponseMessage.Type.NOTIFICATION, msg?.type)
        assertEquals("try again", msg?.payload?.text)
        assertEquals("error", msg?.payload?.type)
        assertNull(msg?.successToken())
    }

    @Test fun `resize carries height, loaded has no payload`() {
        val resize = parseHV3ResponseMessage("""{"type":"RESIZE","payload":{"height":420}}""")
        assertEquals(HV3ResponseMessage.Type.RESIZE, resize?.type)
        assertEquals(420, resize?.payload?.height)
        val loaded = parseHV3ResponseMessage("""{"type":"LOADED"}""")
        assertEquals(HV3ResponseMessage.Type.LOADED, loaded?.type)
        assertNull(loaded?.payload)
    }

    @Test fun `unknown type fails closed`() {
        assertNull(parseHV3ResponseMessage("""{"type":"SOME_FUTURE_MESSAGE"}"""))
    }

    @Test fun `garbage fails closed without throwing`() {
        assertNull(parseHV3ResponseMessage(""))
        assertNull(parseHV3ResponseMessage("not json"))
        assertNull(parseHV3ResponseMessage("""{"type":123}"""))
        assertNull(parseHV3ResponseMessage("""{"type":"HUMAN_VERIFICATION_SUCCESS","payload":"broken"}"""))
    }

    @Test fun `unknown keys are ignored`() {
        val msg = parseHV3ResponseMessage(
            """{"type":"HUMAN_VERIFICATION_SUCCESS","future":true,
                "payload":{"token":"t","type":"captcha","extra":[1,2]}}""",
        )
        assertEquals("t" to "captcha", msg?.successToken())
    }
}
