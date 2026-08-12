// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Message shapes mirror protoncore (GPL-3.0) human-verification/presentation/
// src/main/kotlin/me/proton/core/humanverification/presentation/ui/hv3/
// HV3ResponseMessage.kt — the wire `type` values and the payload field
// names (type/text/token/height) are exact; the parse is fail-closed like
// protoncore's deserializeOrNull: unrecognized or garbage messages parse
// to null and are ignored, never crash the WebView bridge.

package app.alpensync.hv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One message from the verify.proton.me challenge page, received through
 * the `AndroidInterface.dispatch(json)` JS bridge.
 */
internal data class HV3ResponseMessage(
    val type: Type,
    val payload: Payload?,
) {
    /** Wire field names per protoncore's HV3ResponseMessage.Payload. */
    internal data class Payload(
        val type: String? = null,
        val text: String? = null,
        val token: String? = null,
        val height: Int? = null,
    )

    /** Wire values per protoncore's VerificationMessageTypeSerializer. */
    internal enum class Type(val wire: String) {
        SUCCESS("HUMAN_VERIFICATION_SUCCESS"),
        NOTIFICATION("NOTIFICATION"),
        RESIZE("RESIZE"),
        LOADED("LOADED"),
    }

    /** `payload.type` vocabulary of NOTIFICATION messages (protoncore MessageType). */
    internal enum class MessageType(val wire: String) {
        SUCCESS("success"),
        INFO("info"),
        WARNING("warning"),
        ERROR("error"),
    }
}

/**
 * The solved-challenge credentials, present only on SUCCESS messages that
 * carry both a non-blank `payload.token` and `payload.type` (the header
 * values for `x-pm-human-verification-token[-type]`).
 */
internal fun HV3ResponseMessage.successToken(): Pair<String, String>? {
    if (type != HV3ResponseMessage.Type.SUCCESS) return null
    val token = payload?.token?.takeIf { it.isNotBlank() } ?: return null
    val tokenType = payload.type?.takeIf { it.isNotBlank() } ?: return null
    return token to tokenType
}

/**
 * Strict, fail-closed parse: returns null for non-JSON input, a missing or
 * unknown `type`, or a structurally broken message. Unknown keys are
 * ignored (the page may add fields). Never throws — the JS bridge must not
 * crash on a page-controlled string.
 */
internal fun parseHV3ResponseMessage(raw: String): HV3ResponseMessage? = try {
    val root = hv3Json.parseToJsonElement(raw).jsonObject
    val typeWire = root["type"]?.jsonPrimitive?.content ?: return null
    val type = HV3ResponseMessage.Type.entries.firstOrNull { it.wire == typeWire } ?: return null
    // Like protoncore's deserialization: an absent/null payload is fine, a
    // present but wrongly-typed one makes the whole message garbage.
    val payloadNode = root["payload"]
    val payload = when {
        payloadNode == null || payloadNode is JsonNull -> null
        payloadNode is JsonObject -> parsePayload(payloadNode)
        else -> return null
    }
    HV3ResponseMessage(type, payload)
} catch (ignored: IllegalArgumentException) {
    null
}

private val hv3Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun parsePayload(obj: JsonObject): HV3ResponseMessage.Payload = HV3ResponseMessage.Payload(
    type = obj.stringOrNull("type"),
    text = obj.stringOrNull("text"),
    token = obj.stringOrNull("token"),
    height = obj.intOrNull("height"),
)

/** Each field is extracted independently so one broken field loses only itself. */
private fun JsonObject.stringOrNull(key: String): String? = try {
    this[key]?.jsonPrimitive?.content
} catch (ignored: IllegalArgumentException) {
    null
}

private fun JsonObject.intOrNull(key: String): Int? = try {
    this[key]?.jsonPrimitive?.int
} catch (ignored: IllegalArgumentException) {
    null
}
