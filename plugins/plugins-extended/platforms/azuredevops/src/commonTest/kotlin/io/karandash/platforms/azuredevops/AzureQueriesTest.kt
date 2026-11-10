@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.azuredevops

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pull request search, the authenticated account and approval signals from thread comments. */
class AzureQueriesTest {
    private val urls = mutableListOf<String>()

    private fun client(body: String): AzureDevOpsProviderClient {
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond(body, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")))
        }
        return AzureDevOpsProviderClient(HttpClient(engine))
    }

    @Test
    fun `search asks every status by creator and filters text in title or description`() =
        runTest {
            val ann = """{"id": "u1", "displayName": "Ann", "uniqueName": "ann@x.com"}"""
            val azure = client(
                """
                {"value": [
                  {"pullRequestId": 1, "title": "Fix login", "status": "active", "createdBy": $ann, "repository": {"name": "r"}},
                  {"pullRequestId": 2, "title": "Other", "description": "fix LOGIN too", "status": "completed"},
                  {"pullRequestId": 3, "title": "Unrelated", "status": "abandoned"},
                  {"pullRequestId": 4, "title": "fix login again", "status": "abandoned"}
                ]}
                """.trimIndent(),
            )

            val hits = azure.searchPullRequests("o", "p", "r", "u1", "fix login", 3).map { it.toSearchHit("https://dev.azure.com/o/p") }

            assertEquals(listOf("1", "2", "4"), hits.map { it.id })
            assertEquals(
                listOf(MergeRequestSearchHit.OPENED, MergeRequestSearchHit.MERGED, MergeRequestSearchHit.CLOSED),
                hits.map { it.state },
            )
            assertEquals("ann@x.com", hits.first().author)
            assertEquals("https://dev.azure.com/o/p/_git/r/pullrequest/1", hits.first().webUrl)
            val url = Url(urls.single())
            assertEquals("all", url.parameters["searchCriteria.status"])
            assertEquals("u1", url.parameters["searchCriteria.creatorId"])
        }

    @Test
    fun `current username is the account of connection data`() =
        runTest {
            val azure =
                client(
                    """{"authenticatedUser": {"id": "i1", "properties": {"Account": {"${'$'}type": "System.String", "${'$'}value": "bot@x.com"}}}}"""
                )

            assertEquals("bot@x.com", azure.fetchCurrentUsername("o"))
            assertEquals("https://dev.azure.com/o/_apis/connectionData", urls.single())
        }

    @Test
    fun `text comments with an author become comment signals`() {
        val ann = AzureUser(id = "u1", displayName = "Ann", uniqueName = "ann@x.com")
        val threads = listOf(
            AzureThread(
                comments = listOf(
                    AzureCommentContent(1, "LGTM", ann, publishedDate = "t1", commentType = "text"),
                    AzureCommentContent(2, "Voted 10", ann, commentType = "system"),
                    AzureCommentContent(3, "anon", null, commentType = "text"),
                ),
            ),
        )

        val signals = approvalSignals(threads)

        assertEquals(1, signals.size)
        assertEquals(ApprovalMethod.COMMENT_KEYWORD, signals.single().method)
        assertEquals("ann@x.com", signals.single().user.username)
        assertEquals("t1", signals.single().signaledAt)
    }
}
