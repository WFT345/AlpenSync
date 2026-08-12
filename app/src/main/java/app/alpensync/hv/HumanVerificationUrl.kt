// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// URL shape mirrors protoncore (GPL-3.0) human-verification/presentation/
// src/main/kotlin/me/proton/core/humanverification/presentation/ui/hv3/
// HV3DialogFragment.kt (buildUrl, ~line 189). The default prod host is
// https://verify.proton.me per protoncore's configuration/data/src/main/
// kotlin/me/proton/core/configuration/EnvironmentConfiguration.kt:39
// (hv3Url default) + EnvironmentConfigurationTest.kt asserting that value.

package app.alpensync.hv

import java.net.URLEncoder

/** Host of Proton's human-verification challenge page (protoncore default). */
internal const val VERIFY_HOST = "verify.proton.me"

private const val BASE_URL = "https://$VERIFY_HOST"

/**
 * Builds the challenge-page URL exactly as protoncore's HV3 dialog does:
 * `{base}?embed=true&token={startToken}&methods={commaJoined}&theme={1|2}`
 * with every value form-encoded (URLEncoder — so the methods list joins
 * with `,` and the comma itself lands as `%2C`). `theme` is `1` for dark,
 * `2` for light (protoncore's mapping, from the app's dark/light state).
 */
internal fun buildHumanVerificationUrl(
    startToken: String,
    methods: List<String>,
    darkTheme: Boolean,
): String {
    val parameters = listOf(
        "embed" to "true",
        "token" to startToken,
        "methods" to methods.joinToString(","),
        "theme" to if (darkTheme) "1" else "2",
    ).joinToString("&") { (key, value) ->
        "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
    }
    return "$BASE_URL?$parameters"
}
