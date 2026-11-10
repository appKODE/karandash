@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.bitbucket

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pull request search over BBQL, the authenticated account and approval signals from comments. */
class BitbucketQueriesTest {
    private val urls = mutableListOf<String>()

    private fun client(body: String): BitbucketClient {
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return BitbucketClient(httpClient = http, responseCache = null)
    }

    @Test
    fun `search asks every state with a BBQL query and maps the states`() =
        runTest {
            val user = """{"uuid": "{1}", "display_name": "Ann", "nickname": "ann"}"""
            val bitbucket = client(
                """
                {"values": [
                  {"id": 1, "title": "Open", "state": "OPEN", "author": $user,
                   "links": {"html": {"href": "https://bitbucket.org/w/r/pull-requests/1"}}},
                  {"id": 2, "title": "Merged", "state": "MERGED"},
                  {"id": 3, "title": "Declined", "state": "DECLINED"}
                ]}
                """.trimIndent(),
            )

            val hits = bitbucket.searchPullRequests("w", "r", searchQuery("fix \"it\"", "ann"), 10).map { it.toSearchHit() }

            assertEquals(
                listOf(MergeRequestSearchHit.OPENED, MergeRequestSearchHit.MERGED, MergeRequestSearchHit.CLOSED),
                hits.map { it.state },
            )
            assertEquals("ann", hits.first().author)
            assertEquals("https://bitbucket.org/w/r/pull-requests/1", hits.first().webUrl)
            val url = Url(urls.single())
            assertEquals(listOf("OPEN", "MERGED", "DECLINED", "SUPERSEDED"), url.parameters.getAll("state"))
            assertEquals("(title ~ \"fix it\" OR description ~ \"fix it\") AND author.nickname = \"ann\"", url.parameters["q"])
            assertEquals("10", url.parameters["pagelen"])
        }

    @Test
    fun `current user is named by username then nickname then display name`() =
        runTest {
            val bitbucket = client("""{"uuid": "{9}", "display_name": "Bot", "nickname": "bot"}""")

            assertEquals("bot", bitbucket.fetchCurrentUser().handle())
        }

    @Test
    fun `comments with an author and text become comment signals`() {
        val ann = BitbucketUser(uuid = "{1}", displayName = "Ann", nickname = "ann")
        val comments = listOf(
            BitbucketComment(1, BitbucketCommentContent(raw = "LGTM"), ann, createdOn = "t1"),
            BitbucketComment(2, BitbucketCommentContent(raw = ""), ann),
            BitbucketComment(3, BitbucketCommentContent(raw = "anon"), null),
        )

        val signals = approvalSignals(comments)

        assertEquals(1, signals.size)
        assertEquals(ApprovalMethod.COMMENT_KEYWORD, signals.single().method)
        assertEquals("ann", signals.single().user.username)
        assertEquals("t1", signals.single().signaledAt)
    }
}
