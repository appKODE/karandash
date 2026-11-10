package io.karandash.platforms.github

import io.karandash.api.platform.PlatformOperation
import io.karandash.models.DiffRefs
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubServiceRoutingTest {
    private val calls = mutableListOf<String>()

    private suspend fun service(route: (HttpMethod, String) -> Pair<HttpStatusCode, String>): GitHubPlatformService {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            val (status, body) = route(request.method, path)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GitHubPlatformService()
        service.client.rebuild("test") {
            GitHubClient(baseUrl = "https://api.github.test", httpClient = http, logger = NoOpLogger, responseCache = null)
        }
        return service
    }

    private val ok = HttpStatusCode.OK to "{}"

    private companion object {
        const val PULL_REQUEST =
            """{"id": 1, "number": 7, "title": "t", "body": null, "html_url": "u", "state": "open", "draft": false,
            "user": {"login": "me", "id": 1}, "head": {"ref": "h", "sha": "s", "label": "l"},
            "base": {"ref": "b", "sha": "s", "label": "l"}, "created_at": "c", "updated_at": "u", "node_id": "N1"}"""
    }

    @Test
    fun `label draft title and description operations hit the pull request endpoints`() =
        runTest {
            val service =
                service { _, path ->
                    if (path == "/repos/o/r/pulls/7") HttpStatusCode.OK to PULL_REQUEST else ok
                }

            service.addLabels("o/r", "7", listOf("a"))
            service.removeLabels("o/r", "7", listOf("a b", "c"))
            service.updateTitle("o/r", "7", "new title")
            service.updateDescription("o/r", "7", "new body")
            service.setDraft("o/r", "7", true)

            assertTrue("POST /repos/o/r/issues/7/labels" in calls)
            assertTrue("DELETE /repos/o/r/issues/7/labels/a%2520b" in calls || "DELETE /repos/o/r/issues/7/labels/a%20b" in calls)
            assertTrue("DELETE /repos/o/r/issues/7/labels/c" in calls)
            assertEquals(2, calls.count { it == "PATCH /repos/o/r/pulls/7" })
            assertTrue("POST /graphql" in calls)
        }

    @Test
    fun `reviewers are requested and removed and failures come back as a failed result`() =
        runTest {
            val good = service { _, _ -> ok }
            assertTrue(good.addReviewers("o/r", "7", listOf("bob")).isSuccess)
            assertTrue(good.removeReviewers("o/r", "7", listOf("bob")).isSuccess)
            assertEquals(listOf("POST /repos/o/r/pulls/7/requested_reviewers", "DELETE /repos/o/r/pulls/7/requested_reviewers"), calls)

            val bad = service { _, _ -> HttpStatusCode.UnprocessableEntity to "{}" }
            assertTrue(bad.addReviewers("o/r", "7", listOf("bob")).isFailure)
            assertTrue(bad.removeReviewers("o/r", "7", listOf("bob")).isFailure)
        }

    @Test
    fun `approve is skipped when already approved and unapprove is skipped when not`() =
        runTest {
            val approved = """[{"id": 5, "state": "APPROVED", "user": {"login": "me", "id": 1}}]"""
            val service =
                service { method, path ->
                    when {
                        path == "/user" -> HttpStatusCode.OK to """{"login": "me", "id": 1}"""
                        path.endsWith("/reviews") && method == HttpMethod.Get -> HttpStatusCode.OK to approved
                        else -> ok
                    }
                }

            service.approve("o/r", "7")
            assertFalse(calls.any { it.startsWith("POST") })

            service.unapprove("o/r", "7")
            assertTrue("PUT /repos/o/r/pulls/7/reviews/5/dismissals" in calls || calls.any { it.contains("dismissals") })
        }

    @Test
    fun `unapprove without an approval and approve without one act accordingly`() =
        runTest {
            val service =
                service { method, path ->
                    when {
                        path == "/user" -> HttpStatusCode.OK to """{"login": "me", "id": 1}"""
                        path.endsWith("/reviews") && method == HttpMethod.Get -> HttpStatusCode.OK to "[]"
                        else -> ok
                    }
                }

            service.unapprove("o/r", "7")
            assertFalse(calls.any { it.contains("dismissals") })

            service.approve("o/r", "7")
            assertTrue("POST /repos/o/r/pulls/7/reviews" in calls)
        }

    @Test
    fun `inline comments are posted as one review and the marker comment body is found`() =
        runTest {
            val comments = """[{"id": 1, "body": "plain"}, {"id": 2, "body": "has MARK inside"}]"""
            val service =
                service { method, path ->
                    if (method == HttpMethod.Get && path.endsWith("/issues/7/comments")) HttpStatusCode.OK to comments else ok
                }

            assertEquals("7", service.postInlineComments("o/r", "7", emptyList(), DiffRefs("b", "h")))
            assertEquals("has MARK inside", service.findCommentBody("o/r", "7", "MARK"))
            assertNull(service.findCommentBody("o/r", "7", "ABSENT"))
            service.deleteComment("o/r", "7", "2")
            assertTrue("DELETE /repos/o/r/issues/comments/2" in calls)
        }

    @Test
    fun `membership username and auth probes read the user and collaborator endpoints`() =
        runTest {
            val service =
                service { _, path ->
                    when {
                        path == "/user" -> HttpStatusCode.OK to """{"login": "me", "id": 1}"""
                        path.endsWith("/permission") -> HttpStatusCode.OK to """{"permission": "admin"}"""
                        else -> ok
                    }
                }

            assertTrue(service.isProjectMember("o/r", "bob"))
            assertEquals("me", service.currentUsername())
            assertEquals(true, service.checkAuth())
        }

    @Test
    fun `list search and review thread operations read from the api`() =
        runTest {
            val service =
                service { _, path ->
                    when {
                        path == "/repos/o/r/pulls" -> HttpStatusCode.OK to """[{"number": 3}, {"number": 4}]"""
                        path == "/search/issues" -> HttpStatusCode.OK to """{"items": []}"""
                        path.endsWith("/contents/a.txt") -> HttpStatusCode.NotFound to "{}"
                        else -> HttpStatusCode.OK to "[]"
                    }
                }

            assertEquals(listOf("3", "4"), service.listOpenMergeRequestIds("o/r", "main", 10))
            assertEquals(emptyList(), service.searchMergeRequests("o/r", "text", "alice", 5))
            assertNull(service.fetchFileContent("o/r", "main", "a.txt"))
            assertEquals(emptyList(), service.fetchApprovalSignals("o/r", "7"))
            assertTrue(service.supports(PlatformOperation.SEARCH))
        }
}
