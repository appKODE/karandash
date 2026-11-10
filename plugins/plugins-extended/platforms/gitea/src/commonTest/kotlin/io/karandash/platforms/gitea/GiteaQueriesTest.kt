@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gitea

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pull request search on the issue list and approval signals from comments plus reactions. */
class GiteaQueriesTest {
    private val urls = mutableListOf<String>()

    private fun client(answers: Map<String, String>): GiteaProviderClient {
        val engine = MockEngine { request ->
            urls += request.url.toString()
            val body = answers.entries.first { request.url.encodedPath.endsWith(it.key) }.value
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GiteaProviderClient(baseUrl = "https://gitea.example.com", httpClient = http, token = "t")
    }

    @Test
    fun `search lists pulls in every state and maps open merged and closed`() =
        runTest {
            val gitea = client(
                mapOf(
                    "/issues" to """
                        [
                          {"number": 1, "title": "Open", "state": "open", "user": {"id": 1, "login": "ann"},
                           "html_url": "https://gitea.example.com/o/r/pulls/1", "pull_request": {"merged": false}},
                          {"number": 2, "title": "Merged", "state": "closed", "pull_request": {"merged": true}},
                          {"number": 3, "title": "Closed", "state": "closed", "pull_request": {"merged": false}}
                        ]
                    """.trimIndent(),
                ),
            )

            val hits = gitea.searchPullRequests("o", "r", "fix bug", "ann", 10).map { it.toSearchHit() }

            assertEquals(
                listOf(MergeRequestSearchHit.OPENED, MergeRequestSearchHit.MERGED, MergeRequestSearchHit.CLOSED),
                hits.map { it.state },
            )
            assertEquals("ann", hits.first().author)
            assertEquals("1", hits.first().id)
            assertEquals(
                "https://gitea.example.com/api/v1/repos/o/r/issues?type=pulls&state=all&limit=10&q=fix%20bug&created_by=ann",
                urls.single(),
            )
        }

    @Test
    fun `comments and reactions become approval signals of their authors`() =
        runTest {
            val gitea = client(
                mapOf(
                    "/comments" to """[{"id": 7, "body": "LGTM", "user": {"id": 1, "login": "ann"}, "created_at": "t1"}]""",
                    "/reactions" to """[{"content": "+1", "user": {"id": 2, "login": "bob", "full_name": "Bob"}}]""",
                ),
            )

            val signals = approvalSignals(gitea.listIssueComments("o", "r", 5), gitea.listIssueReactions("o", "r", 5))

            assertEquals(listOf(ApprovalMethod.COMMENT_KEYWORD, ApprovalMethod.EMOJI_REACTION), signals.map { it.method })
            assertEquals(listOf("ann", "bob"), signals.map { it.user.username })
            assertEquals(listOf("LGTM", "+1"), signals.map { it.text })
            assertEquals("Bob", signals.last().user.name)
        }
}
