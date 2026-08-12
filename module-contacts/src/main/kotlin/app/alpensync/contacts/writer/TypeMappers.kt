// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// paths core/contacts-writer/.../PhoneTypeMapper.kt, PostalAddressTypeMapper.kt,
// ImProtocolMapper.kt (condensed into one file — each is a tiny pure mapping;
// pcontacts' fromAndroid reverse lookups are M3 write-back scope, not M2).
// Deviation: inputs are the projected type-token lists / URI scheme strings
// from the vcard layer instead of pcontacts' intermediate enums.

package app.alpensync.contacts.writer

import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal

/**
 * vCard `TEL;TYPE=...` tokens → `Phone.TYPE_*`. Pair-aware:
 * `fax+home` → TYPE_FAX_HOME, bare `fax` → OTHER (no Android equivalent).
 * Case-insensitive; unknown tokens collapse to OTHER. Phone numbers
 * themselves are written VERBATIM — never normalized (ADR 0005 Section 6).
 */
object PhoneTypeMapper {

    fun toAndroidType(rawTokens: List<String>): Int {
        val tokens = rawTokens.mapTo(HashSet(rawTokens.size)) { it.lowercase() }
        return faxType(tokens) ?: plainType(tokens)
    }

    /** Pair-aware: fax+home/work → typed fax; bare fax has no Android equivalent. */
    private fun faxType(tokens: Set<String>): Int? = when {
        "fax" !in tokens -> null
        "home" in tokens -> Phone.TYPE_FAX_HOME
        "work" in tokens -> Phone.TYPE_FAX_WORK
        else -> Phone.TYPE_OTHER
    }

    private fun plainType(tokens: Set<String>): Int = when {
        "cell" in tokens || "mobile" in tokens -> Phone.TYPE_MOBILE
        "home" in tokens -> Phone.TYPE_HOME
        "work" in tokens -> Phone.TYPE_WORK
        "pager" in tokens -> Phone.TYPE_PAGER
        "main" in tokens -> Phone.TYPE_MAIN
        else -> Phone.TYPE_OTHER
    }
}

/** vCard `ADR;TYPE=...` tokens → `StructuredPostal.TYPE_*`. */
object PostalAddressTypeMapper {

    fun toAndroidType(rawTokens: List<String>): Int {
        val tokens = rawTokens.mapTo(HashSet(rawTokens.size)) { it.lowercase() }
        return when {
            "home" in tokens -> StructuredPostal.TYPE_HOME
            "work" in tokens -> StructuredPostal.TYPE_WORK
            else -> StructuredPostal.TYPE_OTHER
        }
    }
}

/**
 * vCard `IMPP:` URI scheme → `Im.PROTOCOL_*`. Unknown schemes land on
 * PROTOCOL_CUSTOM with the scheme itself as CUSTOM_PROTOCOL (ContactsContract
 * requires a label whenever PROTOCOL == CUSTOM). The row's TYPE column comes
 * from the vCard TYPE tokens (home/work/other).
 */
object ImProtocolMapper {

    fun protocolToAndroid(scheme: String?): Int =
        SCHEME_TO_PROTOCOL[scheme?.lowercase()] ?: Im.PROTOCOL_CUSTOM

    fun typeToAndroid(rawTokens: List<String>): Int {
        val tokens = rawTokens.mapTo(HashSet(rawTokens.size)) { it.lowercase() }
        return when {
            "home" in tokens -> Im.TYPE_HOME
            "work" in tokens -> Im.TYPE_WORK
            else -> Im.TYPE_OTHER
        }
    }

    private val SCHEME_TO_PROTOCOL: Map<String, Int> = mapOf(
        "xmpp" to Im.PROTOCOL_JABBER,
        "jabber" to Im.PROTOCOL_JABBER,
        "aim" to Im.PROTOCOL_AIM,
        "msn" to Im.PROTOCOL_MSN,
        "msnim" to Im.PROTOCOL_MSN,
        "yahoo" to Im.PROTOCOL_YAHOO,
        "ymsgr" to Im.PROTOCOL_YAHOO,
        "skype" to Im.PROTOCOL_SKYPE,
        "qq" to Im.PROTOCOL_QQ,
        "googletalk" to Im.PROTOCOL_GOOGLE_TALK,
        "gtalk" to Im.PROTOCOL_GOOGLE_TALK,
        "icq" to Im.PROTOCOL_ICQ,
        "netmeeting" to Im.PROTOCOL_NETMEETING,
    )
}
