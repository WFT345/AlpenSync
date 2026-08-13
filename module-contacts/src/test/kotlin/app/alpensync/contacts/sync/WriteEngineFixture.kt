// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.contacts.store.CanonicalVCardStore
import app.alpensync.contacts.vcard.CanonicalContact
import app.alpensync.contacts.vcard.CardEncryptOp
import app.alpensync.contacts.vcard.CardEncryptOutcome
import app.alpensync.contacts.vcard.CardEncryptRequest
import app.alpensync.contacts.vcard.ContactSerializer
import app.alpensync.contacts.vcard.ProjectedContact
import app.alpensync.contacts.vcard.ProjectedEmail
import app.alpensync.contacts.vcard.ProjectedPhone
import app.alpensync.contacts.writer.ApplyResult
import app.alpensync.contacts.writer.ContactsWriterGateway
import app.alpensync.contacts.writer.RawContactOpIntent
import app.alpensync.core.api.dto.BulkDeleteRequest
import app.alpensync.core.api.dto.BulkDeleteResponse
import app.alpensync.core.api.dto.ContactDto
import app.alpensync.core.api.dto.CreateContactResponseBody
import app.alpensync.core.api.dto.CreateContactResponseItem
import app.alpensync.core.api.dto.CreateContactsRequest
import app.alpensync.core.api.dto.CreateContactsResponse
import app.alpensync.core.api.dto.DeleteResponseBody
import app.alpensync.core.api.dto.DeleteResponseItem
import app.alpensync.core.api.dto.UpdateContactRequest
import app.alpensync.core.api.dto.UpdateContactResponse
import app.alpensync.core.api.http.EndpointFamily
import app.alpensync.core.api.http.ProtonServerCodeException
import app.alpensync.core.db.AlpenSyncDatabase
import app.alpensync.core.db.entity.ContactMapEntity
import app.alpensync.core.db.entity.OutboxEntity
import ezvcard.Ezvcard
import ezvcard.VCard
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/**
 * Shared fakes + builders for the write-engine tests: an in-memory Room DB,
 * an identity-wrap canonical store (its crypto is CanonicalVCardStoreTest's
 * concern), a recording API fake whose handlers tests override, and a
 * recording provider gateway. The card crypto is a marking fake — SIGNED
 * cards stay plaintext so tests can assert on payload content.
 */
internal class WriteEngineFixture(val db: AlpenSyncDatabase) {

    val store = CanonicalVCardStore(db.canonicalVCardDao(), { it }, { it }, {})
    val api = FakeWriteApi()
    val writer = FakeWriter()
    val localRows = mutableMapOf<Long, ProjectedContact?>()
    var now = 1_000_000L

    fun newEngine(): ContactWriteEngine = ContactWriteEngine(
        accountName = ACCOUNT,
        db = db,
        pusher = OutboxEntryPusher(
            accountName = ACCOUNT,
            stores = ContactsSyncStore(db, store),
            factory = ContactWriteFactory(ContactSerializer(markingEncryptOp)),
            api = api,
            readLocal = { rawId, _ -> localRows[rawId] },
            writer = writer,
            clock = { now },
        ),
        clock = { now },
    )

    suspend fun seedMapping(
        id: String,
        rawId: Long,
        uid: String? = "urn:uuid:$id",
        status: Int = ContactMapEntity.Status.CLEAN,
        lastKnownHash: String? = null,
        contentHash: String = "content-$id",
        photoHash: String? = null,
        modifyTime: Long = 1L,
    ) = db.contactMapDao().upsert(
        ContactMapEntity(
            accountName = ACCOUNT,
            protonContactId = id,
            protonUid = uid,
            androidRawContactId = rawId,
            modifyTime = modifyTime,
            contentHash = contentHash,
            photoHash = photoHash,
            isVerified = true,
            syncStatus = status,
            lastError = null,
            lastSyncedAt = 1L,
            lastKnownServerPayloadHash = lastKnownHash,
        ),
    )

    suspend fun seedOutbox(
        id: String,
        op: Int,
        createdAt: Long? = null,
        payloadHash: String = "payload-$id",
    ): Long = db.outboxDao().insert(
        OutboxEntity(
            accountName = ACCOUNT,
            protonContactId = id,
            opType = op,
            payloadHash = payloadHash,
            createdAt = createdAt ?: now,
        ),
    )

    suspend fun outboxRow(id: String): OutboxEntity? = db.outboxDao().findByContact(ACCOUNT, id).singleOrNull()

    companion object {
        const val ACCOUNT = "default"

        /** SIGNED cards keep their plaintext (assertable); the encrypted card is marked, not encrypted. */
        val markingEncryptOp: CardEncryptOp = { request ->
            when (request) {
                is CardEncryptRequest.SignOnly -> CardEncryptOutcome(request.plaintext, "SIG")
                is CardEncryptRequest.EncryptAndSign ->
                    CardEncryptOutcome("ENC:" + request.plaintext, "SIG")
            }
        }

        fun vcard(text: String): VCard = Ezvcard.parse(text).first()

        fun canonicalOf(id: String, text: String): CanonicalContact = CanonicalContact.ofVCard(id, vcard(text))

        fun httpError(status: Int): ProtonServerCodeException = ProtonServerCodeException(
            protonCode = null,
            endpointFamily = EndpointFamily.CONTACTS,
            httpStatus = status,
            cause = HttpException(Response.error<Any>(status, "".toResponseBody(null))),
        )

        const val BASE_VCARD = "BEGIN:VCARD\r\n" +
            "VERSION:4.0\r\n" +
            "FN:Alice Base\r\n" +
            "UID:urn:uuid:pc-1\r\n" +
            "EMAIL:alice@home.example\r\n" +
            "TEL;TYPE=cell:+1-111\r\n" +
            "END:VCARD\r\n"
    }
}

/** Records calls; per-op handlers default to the smallest honest success and are overridden per test. */
internal class FakeWriteApi : ContactWriteApi {
    val calls = mutableListOf<String>()
    val createRequests = mutableListOf<CreateContactsRequest>()
    val updateRequests = mutableListOf<UpdateContactRequest>()
    val deleteRequests = mutableListOf<BulkDeleteRequest>()

    var createHandler: suspend (CreateContactsRequest) -> CreateContactsResponse = {
        CreateContactsResponse(
            code = 1000,
            responses = listOf(
                CreateContactResponseItem(0, CreateContactResponseBody(1000, ContactDto(id = "srv-created", modifyTime = 42L))),
            ),
        )
    }
    var updateHandler: suspend (String, UpdateContactRequest) -> UpdateContactResponse = { _, _ ->
        UpdateContactResponse(code = 1000, contact = ContactDto(id = "ignored", modifyTime = 43L))
    }
    var deleteHandler: suspend (BulkDeleteRequest) -> BulkDeleteResponse = { request ->
        BulkDeleteResponse(
            code = 1000,
            responses = request.ids.map { DeleteResponseItem(it, DeleteResponseBody(1000)) },
        )
    }
    var fetchCanonicalHandler: suspend (String) -> CanonicalContact? = { null }

    override suspend fun create(request: CreateContactsRequest): CreateContactsResponse {
        calls += "create"
        createRequests += request
        return createHandler(request)
    }

    override suspend fun update(protonContactId: String, request: UpdateContactRequest): UpdateContactResponse {
        calls += "update"
        updateRequests += request
        return updateHandler(protonContactId, request)
    }

    override suspend fun delete(request: BulkDeleteRequest): BulkDeleteResponse {
        calls += "delete"
        deleteRequests += request
        return deleteHandler(request)
    }

    override suspend fun fetchCanonical(protonContactId: String): CanonicalContact? {
        calls += "fetch"
        return fetchCanonicalHandler(protonContactId)
    }
}

/** Recording provider gateway: the intents are the assertions. */
internal class FakeWriter : ContactsWriterGateway {
    val applied = mutableListOf<List<RawContactOpIntent>>()
    var failApply: (() -> Unit)? = null

    fun intents(): List<RawContactOpIntent> = applied.flatten()

    override fun readExistingRawIds(): Map<String, Long> = emptyMap()

    override fun apply(intents: List<RawContactOpIntent>): ApplyResult {
        failApply?.invoke()
        applied += intents
        return ApplyResult()
    }
}

/** A local projection carrying a display name, one email, and optionally extra phones. */
internal fun localProjection(id: String, name: String, vararg extraPhones: String) =
    LocalChangeDetector.emptyProjection(id).copy(
        displayName = name,
        emails = listOf(ProjectedEmail("alice@home.example", emptyList(), false)),
        phones = listOf(ProjectedPhone("+1-111", listOf("cell"), false)) +
            extraPhones.map { ProjectedPhone(it, emptyList(), false) },
    )
