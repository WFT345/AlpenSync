// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors
// Adapted from pcontacts (GPL-3.0), https://github.com/andreabenetton/pcontacts @ bf9b0c5,
// paths core/proton-api/.../auth/ProtonAuthApi.kt, .../users/ProtonUsersApi.kt,
// .../addresses/ProtonAddressesApi.kt (merged into one file on purpose).

package app.alpensync.core.api

import app.alpensync.core.api.dto.AuthRequest
import app.alpensync.core.api.dto.AuthResponse
import app.alpensync.core.api.dto.ContactsPageResponse
import app.alpensync.core.api.dto.GetAddressesResponse
import app.alpensync.core.api.dto.GetContactResponse
import app.alpensync.core.api.dto.GetKeySaltsResponse
import app.alpensync.core.api.dto.GetLabelsResponse
import app.alpensync.core.api.dto.GetUserResponse
import app.alpensync.core.api.dto.InfoRequest
import app.alpensync.core.api.dto.InfoResponse
import app.alpensync.core.api.dto.RefreshRequest
import app.alpensync.core.api.dto.RefreshResponse
import app.alpensync.core.api.dto.TwoFactorRequest
import app.alpensync.core.api.dto.TwoFactorResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * EVERY Proton endpoint path this app calls, in one file (ADR 0004 Section
 * 2, plan Section 10.4). Proton's API is undocumented and changes without
 * notice; when it moves, this file is the patch point.
 *
 * Three route families coexist server-side (research notes Section 7):
 *   - the bare `/auth/` family — oldest (hydroxide)
 *   - the `auth/v4/` family — protoncore's session-first flow (needs an
 *     unauthenticated session token first; NOT implemented)
 *   - `core/v4/auth/` + unprefixed `auth/refresh` — the family below, the
 *     only one live-verified for a third-party client (pcontacts,
 *     2026-05-24, appversion window re-checked 2026-07-28).
 *
 * Path strings and HTTP verbs are not parameterized; mistyping any of them
 * is an instant production break. Note `auth/info` is POST (the body
 * carries Username + Intent), and `auth/refresh` is genuinely unprefixed.
 */
interface ProtonApi {

    @POST("core/v4/auth/info")
    suspend fun getInfo(@Body request: InfoRequest): InfoResponse

    @POST("core/v4/auth")
    suspend fun auth(@Body request: AuthRequest): AuthResponse

    @POST("core/v4/auth/2fa")
    suspend fun auth2FA(@Body request: TwoFactorRequest): TwoFactorResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): RefreshResponse

    /** Logout / session revocation. Live-verified 200 by pcontacts. */
    @DELETE("core/v4/auth")
    suspend fun revoke()

    @GET("core/v4/users")
    suspend fun getUser(): GetUserResponse

    @GET("core/v4/keys/salts")
    suspend fun getKeySalts(): GetKeySaltsResponse

    @GET("core/v4/addresses")
    suspend fun getAddresses(): GetAddressesResponse

    /**
     * Cheap per-contact metadata listing — no `Cards[]`. Pages are
     * 0-indexed; stop on a short page; `Total` is ignored (research notes
     * Section 1.1). `Email` and `LabelID` filters are XOR server-side (both
     * together → HTTP 400); this surface exposes only the label filter.
     */
    // UNVERIFIED: envelope + paging behavior under our appversion pin —
    // live-verified by pcontacts 2026-05-24, first own check is the M2
    // acceptance run (docs/research/m2-contacts-notes.md Section 9).
    @GET("contacts/v4/contacts")
    suspend fun listContacts(
        @Query("Page") page: Int,
        @Query("PageSize") pageSize: Int,
        @Query("LabelID") labelIdFilter: String? = null,
    ): ContactsPageResponse

    /**
     * Full contact incl. `Cards[]`. Fetch only when the listing's ModifyTime
     * advanced — never speculatively for the whole list. The bulk export
     * endpoint is deliberately NOT exposed (ADR 0005 Section 4).
     */
    @GET("contacts/v4/contacts/{id}")
    suspend fun getContact(@Path("id") id: String): GetContactResponse

    /**
     * Labels of one kind. M2 passes [app.alpensync.core.api.dto.LabelType.CONTACT_GROUP]
     * (Type 2) — contact groups are read-only at M2 (ADR 0005).
     */
    @GET("core/v4/labels")
    suspend fun listLabels(@Query("Type") type: Int): GetLabelsResponse
}
