// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.core.api

import app.alpensync.core.api.dto.BulkDeleteRequest
import app.alpensync.core.api.dto.ContactCardBundle
import app.alpensync.core.api.dto.ContactCardDto
import app.alpensync.core.api.dto.CreateContactsRequest
import app.alpensync.core.api.dto.UpdateContactRequest
import app.alpensync.core.api.dto.failedIds
import app.alpensync.core.api.dto.failedIndexes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the M3a write endpoints through the real Retrofit/OkHttp stack
 * against MockWebServer (offline; Rule 1): verbs + paths, exact request
 * bodies, per-index/per-ID sub-response parsing incl. partial failure, and
 * fail-closed behavior on a malformed sub-response. Shapes per research
 * notes Section 2.1-2.3.
 */
class ContactsWriteEndpointsTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ProtonApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = ProtonApiFactory(
            config = ProtonApiConfig(baseUrl = server.url("/").toString()),
            session = InMemorySession(uid = "uid-1", accessToken = "token-1"),
        ).api
    }

    @After fun tearDown() = server.shutdown()

    @Test fun createPostsSingleItemBatchAndReadsPerIndexSubResponses() = runTest {
        server.enqueue(
            json(
                """{"Code":1000,"Responses":[{"Index":0,"Response":{"Code":1000,""" +
                    """"Contact":{"ID":"srv-1","UID":"urn:uuid:u-1","ModifyTime":55}}}]}""",
            ),
        )

        val response = api.createContacts(
            CreateContactsRequest(listOf(ContactCardBundle(cards()))),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/contacts/v4/contacts", request.path)
        assertEquals(
            """{"Contacts":[{"Cards":[""" +
                """{"Type":2,"Data":"BEGIN:VCARD","Signature":"sig"},""" +
                """{"Type":3,"Data":"armored","Signature":"sig2"}]}],""" +
                """"Overwrite":0,"Labels":0}""",
            request.body.readUtf8(),
        )
        assertEquals("srv-1", response.responses.single().response.contact?.id)
        assertEquals("urn:uuid:u-1", response.responses.single().response.contact?.uid)
        assertTrue(response.failedIndexes().isEmpty())
    }

    @Test fun createPartialFailureSurfacesTheFailingIndex() = runTest {
        server.enqueue(
            json(
                """{"Code":1000,"Responses":[{"Index":0,"Response":{"Code":1000,"Contact":{"ID":"ok"}}},""" +
                    """{"Index":1,"Response":{"Code":12001}}]}""",
            ),
        )

        // We never batch, but the walker must catch per-index failures (HTTP 200 says nothing).
        val response = api.createContacts(CreateContactsRequest(listOf(ContactCardBundle(cards()))))

        assertEquals(listOf(1), response.failedIndexes())
    }

    @Test fun updatePutsWholeCardsReplacement() = runTest {
        server.enqueue(json("""{"Code":1000,"Contact":{"ID":"c1","ModifyTime":77}}"""))

        val response = api.updateContact("c1", UpdateContactRequest(cards()))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/contacts/v4/contacts/c1", request.path)
        assertEquals(
            """{"Cards":[{"Type":2,"Data":"BEGIN:VCARD","Signature":"sig"},""" +
                """{"Type":3,"Data":"armored","Signature":"sig2"}]}""",
            request.body.readUtf8(),
        )
        assertEquals(77L, response.contact?.modifyTime)
    }

    @Test fun bulkDeleteIsPutWithPerIdSubResponses() = runTest {
        server.enqueue(
            json(
                """{"Code":1000,"Responses":[{"ID":"c1","Response":{"Code":1000}},""" +
                    """{"ID":"c2","Response":{"Code":2501}}]}""",
            ),
        )

        val response = api.deleteContacts(BulkDeleteRequest(listOf("c1", "c2")))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/contacts/v4/contacts/delete", request.path)
        assertEquals("""{"IDs":["c1","c2"]}""", request.body.readUtf8())
        assertEquals(listOf("c2"), response.failedIds())
    }

    @Test fun deleteSubResponseWithoutIdFailsClosed() = runTest {
        server.enqueue(json("""{"Code":1000,"Responses":[{"Response":{"Code":1000}}]}"""))

        val error = runCatching { api.deleteContacts(BulkDeleteRequest(listOf("c1"))) }.exceptionOrNull()
        assertTrue("expected a SerializationException, got $error", error is SerializationException)
    }

    private fun cards() = listOf(
        ContactCardDto(type = 2, data = "BEGIN:VCARD", signature = "sig"),
        ContactCardDto(type = 3, data = "armored", signature = "sig2"),
    )

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}
