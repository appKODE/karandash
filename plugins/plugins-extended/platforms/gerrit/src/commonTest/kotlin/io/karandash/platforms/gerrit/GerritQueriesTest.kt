@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gerrit

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Change search, the authenticated username and approval signals from change messages. */
class GerritQueriesTest {
    private val urls = mutableListOf<String>()

    private fun http(body: String) =
        HttpClient(
            MockEngine { request ->
                urls += request.url.toString()
                respond(")]}'\n$body", HttpStatusCode.OK)
            },
        )

    @Test
    fun `search queries project message and owner and maps NEW MERGED and ABANDONED`() =
        runTest {
            val owner = """"owner": {"_account_id": 1, "username": "ann"}"""
            val body = """
                [
                  {"id": "a", "_number": 1, "subject": "Open", "project": "p", "branch": "main", "status": "NEW", $owner},
                  {"id": "b", "_number": 2, "subject": "Merged", "project": "p", "branch": "main", "status": "MERGED", $owner},
                  {"id": "c", "_number": 3, "subject": "Gone", "project": "p", "branch": "main", "status": "ABANDONED", $owner}
                ]
            """.trimIndent()
            val client = GerritProviderClient("https://gerrit.example.com", http(body), "pw", username = "bot")

            val hits = client.searchChanges("p", "fix \"bug\"", "ann", 10).map { it.toSearchHit() }

            assertEquals(
                listOf(MergeRequestSearchHit.OPENED, MergeRequestSearchHit.MERGED, MergeRequestSearchHit.CLOSED),
                hits.map { it.state },
            )
            assertEquals(listOf("1", "2", "3"), hits.map { it.id })
            assertEquals("ann", hits.first().author)
            val url = urls.single()
            assertTrue("o=DETAILED_ACCOUNTS" in url, url)
            assertTrue("message%3A%22fix%20bug%22" in url || "message:%22fix%20bug%22" in url, url)
        }

    @Test
    fun `current username comes from the authenticated account`() =
        runTest {
            val client =
                GerritClient("https://gerrit.example.com", "pw", http("""{"_account_id": 5, "username": "bot"}"""), username = "bot")

            assertEquals("bot", client.fetchCurrentUsername())
            assertEquals(5L, client.fetchCurrentAccountId())
            assertEquals(1, urls.size)
        }

    @Test
    fun `change messages with an author become comment signals`() {
        val messages = listOf(
            GerritChangeMessageInfo("1", GerritAccountInfo(7, name = "Ann", username = "ann"), "t1", "LGTM"),
            GerritChangeMessageInfo("2", null, "t2", "Uploaded patch set 2"),
        )

        val signals = approvalSignals(messages)

        assertEquals(1, signals.size)
        assertEquals(ApprovalMethod.COMMENT_KEYWORD, signals.single().method)
        assertEquals("ann", signals.single().user.username)
        assertEquals("LGTM", signals.single().text)
        assertEquals("t1", signals.single().signaledAt)
    }
}
