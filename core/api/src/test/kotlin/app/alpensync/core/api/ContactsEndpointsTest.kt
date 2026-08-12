// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.api

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the contacts endpoints of [ProtonApi] through the real
 * Retrofit/OkHttp stack against MockWebServer (offline; Rule 1): paths,
 * paging stop conditions, label Type, DTO tolerance, and fail-closed
 * behavior on a missing required field.
 */
class ContactsEndpointsTest {

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

    @Test fun metadataPagerWalksUntilShortPage() = runTest {
        server.enqueue(json(page(contact("c1"), contact("c2"))))
        server.enqueue(json(page(contact("c3"))))

        val items = ContactsMetadataPager(api, pageSize = 2).metadata().toList()

        assertEquals(listOf("c1", "c2", "c3"), items.map { it.id })
        assertEquals(2, server.requestCount)
        assertEquals("/contacts/v4/contacts?Page=0&PageSize=2", server.takeRequest().path)
        assertEquals("/contacts/v4/contacts?Page=1&PageSize=2", server.takeRequest().path)
    }

    @Test fun metadataPagerStopsOnEmptyPageAndSendsLabelFilter() = runTest {
        server.enqueue(json("""{"Code":1000,"Contacts":[],"Total":0}"""))

        val items = ContactsMetadataPager(api, pageSize = 2).metadata(labelIdFilter = "lbl_1").toList()

        assertTrue(items.isEmpty())
        assertEquals("/contacts/v4/contacts?Page=0&PageSize=2&LabelID=lbl_1", server.takeRequest().path)
    }

    @Test fun getContactParsesCardsAndToleratesUnknownFields() = runTest {
        server.enqueue(
            json(
                """{"Code":1000,"FutureField":true,"Contact":{"ID":"c1","Name":"Alice",""" +
                    """"UID":"urn:uuid:alice-1","Size":321,"CreateTime":100,"ModifyTime":200,""" +
                    """"LabelIDs":["lbl_1"],""" +
                    """"ContactEmails":[{"ID":"e1","Email":"alice@example.com","ContactID":"c1"}],""" +
                    """"Cards":[{"Type":2,"Data":"BEGIN:VCARD","Signature":"-----BEGIN PGP SIGNATURE-----"},""" +
                    """{"Type":3,"Data":"-----BEGIN PGP MESSAGE-----","Signature":null},""" +
                    """{"Type":0,"Data":"BEGIN:VCARD"}],"Unknown":"ignored"}}""",
            ),
        )

        val contact = api.getContact("c1").contact

        assertEquals("c1", contact.id)
        assertEquals(200L, contact.modifyTime)
        assertEquals(listOf("lbl_1"), contact.labelIds)
        assertEquals("alice@example.com", contact.contactEmails.single().email)
        assertEquals(3, contact.cards.size)
        assertEquals(3, contact.cards[1].type)
        assertNull(contact.cards[1].signature)
        assertNull(contact.cards[2].signature)
        assertEquals("/contacts/v4/contacts/c1", server.takeRequest().path)
    }

    @Test fun listLabelsSendsContactGroupType() = runTest {
        server.enqueue(
            json(
                """{"Code":1000,"Labels":[""" +
                    """{"ID":"lbl_1","Name":"Friends","Type":2},{"ID":"lbl_2","Name":"Work","Type":2}]}""",
            ),
        )

        val labels = api.listLabels(app.alpensync.core.api.dto.LabelType.CONTACT_GROUP).labels

        assertEquals(listOf("Friends", "Work"), labels.map { it.name })
        assertEquals("/core/v4/labels?Type=2", server.takeRequest().path)
    }

    @Test fun metadataRowWithoutIdFailsClosed() = runTest {
        // Strict parsing (plan Rule 5): a required identity field missing is
        // a hard deserialization error, never a blank-key contact row.
        server.enqueue(json("""{"Code":1000,"Contacts":[{"Name":"no-id"}],"Total":1}"""))

        val error = runCatching { api.listContacts(page = 0, pageSize = 10) }.exceptionOrNull()
        assertTrue("expected a SerializationException, got $error", error is SerializationException)
    }

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun contact(id: String) = """{"ID":"$id","Name":"n-$id","ModifyTime":200,"LabelIDs":[]}"""

    private fun page(vararg contacts: String) =
        """{"Code":1000,"Contacts":[${contacts.joinToString(",")}],"Total":3}"""
}
