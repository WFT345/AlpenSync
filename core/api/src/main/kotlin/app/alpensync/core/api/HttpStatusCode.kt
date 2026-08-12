// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// path core/proton-api/src/main/kotlin/io/pcontacts/core/proton/api/HttpStatusCode.kt

package app.alpensync.core.api

import retrofit2.HttpException

/**
 * Extracts the HTTP status code from a [Throwable] if it wraps a Retrofit
 * [HttpException]. Returns null for non-HTTP errors.
 *
 * Defined here (in `:core:api`, where Retrofit is on the classpath) so
 * callers like `:core:auth` can inspect HTTP codes without depending on
 * Retrofit directly.
 */
fun Throwable.httpStatusCode(): Int? = (this as? HttpException)?.code()
