package io.karandash.platforms.github

import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GitHub adapter and client. */
class GitHubRegressionTest {
    private fun user(login: String) = GitHubUser(login = login, id = login.length.toLong())

    private fun pr(requested: List<GitHubUser> = emptyList()) =
        GitHubPullRequest(
            id = 1L,
            number = 7,
            title = "t",
            body = null,
            htmlUrl = "https://github.com/o/r/pull/7",
            state = "open",
            draft = false,
            user = user("author"),
            head = GitHubRef("f", "abc", "o:f"),
            base = GitHubRef("main", "def", "o:main"),
            requestedReviewers = requested,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
            nodeId = "PR_node7",
        )

    private fun review(
        id: Long,
        login: String,
        state: String,
    ) = GitHubReview(id = id, user = user(login), state = state, submittedAt = "2024-01-0${id}T00:00:00Z")

    private fun toMr(
        reviews: List<GitHubReview>,
        requested: List<GitHubUser> = emptyList(),
    ) = GitHubAdapter.toMergeRequest(pr(requested), emptyList(), emptyList(), reviews)

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(engine: MockEngine) =
        GitHubClient(
            baseUrl = "https://api.github.com",
            token = "t",
            httpClient = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } },
        )

    private fun prJson() = KarandashJson.Default.encodeToString(GitHubPullRequest.serializer(), pr())

    @Test
    fun `later CHANGES_REQUESTED cancels an earlier approval`() {
        val mr = toMr(listOf(review(1, "bob", "APPROVED"), review(2, "bob", "CHANGES_REQUESTED")))
        assertEquals(emptyList(), mr.approvals)
    }

    @Test
    fun `repeated approvals of one user count once`() {
        val mr = toMr(listOf(review(1, "bob", "APPROVED"), review(2, "bob", "COMMENTED"), review(3, "bob", "APPROVED")))
        assertEquals(listOf("bob"), mr.approvals.map { it.user.username })
    }

    @Test
    fun `review authors are reviewers after the request was cleared`() {
        val mr = toMr(listOf(review(1, "bob", "APPROVED"), review(2, "ann", "COMMENTED")), listOf(user("carl")))
        assertEquals(setOf("carl", "bob", "ann"), mr.reviewers.map { it.username }.toSet())
        assertEquals(3, mr.reviewers.size)
    }

    @Test
    fun `check runs follow pagination`() =
        runTest {
            val engine = MockEngine { request ->
                val page2 = "page=2" in request.url.toString()
                val body = if (page2) {
                    """{"check_runs":[{"name":"b","status":"completed","conclusion":"failure"}]}"""
                } else {
                    """{"check_runs":[{"name":"a","status":"completed","conclusion":"success"}]}"""
                }
                val headers = if (page2) {
                    jsonHeaders
                } else {
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.Link to listOf("<https://api.github.com/repos/o/r/commits/abc/check-runs?page=2>; rel=\"next\""),
                    )
                }
                respond(ByteReadChannel(body), HttpStatusCode.OK, headers)
            }
            val runs = client(engine).fetchCheckRuns("o", "r", "abc")
            assertEquals(listOf("a", "b"), runs.checkRuns.map { it.name })
        }

    @Test
    fun `setDraft uses the GraphQL mutation not PATCH`() =
        runTest {
            val seen = mutableListOf<String>()
            var sent = ""
            val engine = MockEngine { request ->
                seen += "${request.method.value} ${request.url.encodedPath}"
                if (request.url.encodedPath == "/graphql") {
                    sent = request.body.toByteArray().decodeToString()
                    respond(ByteReadChannel("""{"data":{}}"""), HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond(ByteReadChannel(prJson()), HttpStatusCode.OK, jsonHeaders)
                }
            }
            client(engine).setDraft("o", "r", 7, true)
            assertTrue("convertPullRequestToDraft" in sent && "PR_node7" in sent, sent)
            assertTrue(seen.none { it.startsWith("PATCH") }, seen.toString())
        }

    @Test
    fun `setDraft false marks ready for review`() =
        runTest {
            var sent = ""
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/graphql") {
                    sent = request.body.toByteArray().decodeToString()
                    respond(ByteReadChannel("""{"data":{}}"""), HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond(ByteReadChannel(prJson()), HttpStatusCode.OK, jsonHeaders)
                }
            }
            client(engine).setDraft("o", "r", 7, false)
            assertTrue("markPullRequestReadyForReview" in sent, sent)
        }

    @Test
    fun `GraphQL errors are surfaced`() =
        runTest {
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/graphql") {
                    respond(ByteReadChannel("""{"errors":[{"message":"nope"}]}"""), HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond(ByteReadChannel(prJson()), HttpStatusCode.OK, jsonHeaders)
                }
            }
            val failure = runCatching { client(engine).setDraft("o", "r", 7, true) }.exceptionOrNull()
            assertTrue(failure?.message?.contains("nope") == true, failure?.message)
        }
}
