// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// paths core/contacts-writer/.../PhoneTypeMapper.kt, PostalAddressTypeMapper.kt,
// ImProtocolMapper.kt (condensed into one file — each is a tiny pure mapping;
// pcontacts' fromAndroid reverse lookups are M3 write-back scope, not M2).
// Deviation: inputs are the projected type-token lists / URI scheme strings
// from the vcard layer instead of pcontacts' intermediate enums.

package app.alpensync.contacts.writer

import android.provider.ContactsContract.CommonDataKinds.Email
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

    /**
     * The M3b read-back direction (dirty detection): Android `Phone.TYPE_*` →
     * vCard tokens. Canonical-inverse of [toAndroidType] on the Android int
     * domain — `toAndroidType(fromAndroidType(t)) == t` for every TYPE the
     * provider can hold; compound tokens (e.g. `cell+home`) collapse to the
     * primary int's tokens, which the reconciler repairs from the baseline.
     */
    fun fromAndroidType(type: Int): List<String> = when (type) {
        Phone.TYPE_MOBILE -> listOf("cell")
        Phone.TYPE_HOME -> listOf("home")
        Phone.TYPE_WORK -> listOf("work")
        Phone.TYPE_PAGER -> listOf("pager")
        Phone.TYPE_MAIN -> listOf("main")
        Phone.TYPE_FAX_HOME -> listOf("fax", "home")
        Phone.TYPE_FAX_WORK -> listOf("fax", "work")
        else -> emptyList()
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

    /** Read-back inverse (M3b): canonical tokens per Android type; see [PhoneTypeMapper.fromAndroidType]. */
    fun fromAndroidType(type: Int): List<String> = when (type) {
        StructuredPostal.TYPE_HOME -> listOf("home")
        StructuredPostal.TYPE_WORK -> listOf("work")
        else -> emptyList()
    }
}

/**
 * Read-back-only mapper (M3b): the writer always stores emails as
 * `Email.TYPE_OTHER` (ContactDataOps), so a non-OTHER type on read-back is a
 * genuine phone-side type edit. `Email.TYPE_*` → vCard tokens.
 */
object EmailTypeMapper {

    fun fromAndroidType(type: Int): List<String> = when (type) {
        Email.TYPE_HOME -> listOf("home")
        Email.TYPE_WORK -> listOf("work")
        else -> emptyList()
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

    /**
     * Read-back inverse (M3b): Android `Im.PROTOCOL_*` → the vCard URI
     * scheme. Aliases collapse to the canonical scheme (`jabber` → `xmpp`);
     * PROTOCOL_CUSTOM returns the row's CUSTOM_PROTOCOL string (the writer
     * stores the scheme there — ContactsContract requires it).
     */
    fun protocolFromAndroid(protocol: Int, customProtocol: String?): String? =
        if (protocol == Im.PROTOCOL_CUSTOM) {
            customProtocol?.takeIf { it.isNotBlank() }
        } else {
            PROTOCOL_TO_SCHEME[protocol]
        }

    private val PROTOCOL_TO_SCHEME: Map<Int, String> = mapOf(
        Im.PROTOCOL_JABBER to "xmpp",
        Im.PROTOCOL_AIM to "aim",
        Im.PROTOCOL_MSN to "msn",
        Im.PROTOCOL_YAHOO to "yahoo",
        Im.PROTOCOL_SKYPE to "skype",
        Im.PROTOCOL_QQ to "qq",
        Im.PROTOCOL_GOOGLE_TALK to "googletalk",
        Im.PROTOCOL_ICQ to "icq",
        Im.PROTOCOL_NETMEETING to "netmeeting",
    )

    /** Read-back inverse (M3b): `Im.TYPE_*` → vCard TYPE tokens. */
    fun typeFromAndroid(type: Int): List<String> = when (type) {
        Im.TYPE_HOME -> listOf("home")
        Im.TYPE_WORK -> listOf("work")
        else -> emptyList()
    }
}
