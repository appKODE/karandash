package io.karandash.platforms.github

import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitHubClientTest {
    @Test
    fun testClientCreation() =
        runTest {
            val client = GitHubClient()
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testClientWithCustomBaseUrl() =
        runTest {
            val client = GitHubClient(baseUrl = "https://github.enterprise.com/api/v3")
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testClientWithToken() =
        runTest {
            val client = GitHubClient(token = "test-token")
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testClientWithAllParameters() =
        runTest {
            val client = GitHubClient(
                baseUrl = "https://github.enterprise.com/api/v3",
                token = "test-token"
            )
            assertNotNull(client)
            client.close()
        }

    @Test
    fun `fetchCurrentUsername returns login from user endpoint`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"login": "bot-user", "id": 42}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitHubClient(
                baseUrl = "https://api.github.com",
                token = "test-token",
                httpClient = httpClient
            )

            val username = client.fetchCurrentUsername()
            assertEquals("bot-user", username)

            // Verify caching
            val username2 = client.fetchCurrentUsername()
            assertEquals("bot-user", username2)

            client.close()
        }

    @Test
    fun `fetchCurrentUsername returns null on API failure`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"Bad credentials"}"""),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitHubClient(
                baseUrl = "https://api.github.com",
                token = "bad-token",
                httpClient = httpClient
            )

            val username = client.fetchCurrentUsername()
            assertEquals(null, username)

            client.close()
        }

    @Test
    fun `isApprovedByCurrentUser returns true when user has approved`() =
        runTest {
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                if (url.endsWith("/user")) {
                    respond(
                        content = ByteReadChannel("""{"login": "bot-user", "id": 42}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        content = ByteReadChannel(
                            """[
                                {"id": 1, "user": {"login": "bot-user", "id": 42}, "state": "APPROVED"}
                            ]"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitHubClient(
                baseUrl = "https://api.github.com",
                token = "test-token",
                httpClient = httpClient
            )

            assertTrue(client.isApprovedByCurrentUser("owner", "repo", 1))

            client.close()
        }

    @Test
    fun `isApprovedByCurrentUser returns false when user has not approved`() =
        runTest {
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                if (url.endsWith("/user")) {
                    respond(
                        content = ByteReadChannel("""{"login": "bot-user", "id": 42}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        content = ByteReadChannel(
                            """[
                                {"id": 1, "user": {"login": "other-user", "id": 99}, "state": "APPROVED"}
                            ]"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitHubClient(
                baseUrl = "https://api.github.com",
                token = "test-token",
                httpClient = httpClient
            )

            assertFalse(client.isApprovedByCurrentUser("owner", "repo", 1))

            client.close()
        }

    @Test
    fun `listIssueComments paginates across multiple pages`() =
        runTest {
            val page1Body = (1..100).joinToString(",", "[", "]") {
                """{"id": $it, "body": "comment $it", "user": {"login": "user", "id": 1}}"""
            }
            val page2Body = (101..125).joinToString(",", "[", "]") {
                """{"id": $it, "body": "comment $it", "user": {"login": "user", "id": 1}}"""
            }
            var requestCount = 0
            val mockEngine = MockEngine {
                requestCount++
                if (requestCount == 1) {
                    respond(
                        content = ByteReadChannel(page1Body),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            HttpHeaders.Link to listOf(
                                """<https://api.github.com/repos/owner/repo/issues/1/comments?page=2&per_page=100>; rel="next""""
                            )
                        )
                    )
                } else {
                    respond(
                        content = ByteReadChannel(page2Body),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitHubClient(
                baseUrl = "https://api.github.com",
                token = "test-token",
                httpClient = httpClient
            )

            val comments = client.listIssueComments("owner", "repo", 1)
            assertEquals(125, comments.size)
            assertEquals(2, requestCount)

            client.close()
        }

    @Test
    fun `listIssueComments single page no extra requests`() =
        runTest {
            val body = """[{"id": 1, "body": "only comment", "user": {"login": "user", "id": 1}}]"""
            var requestCount = 0
            val mockEngine = MockEngine {
                requestCount++
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitHubClient(
                baseUrl = "https://api.github.com",
                token = "test-token",
                httpClient = httpClient
            )

            val comments = client.listIssueComments("owner", "repo", 1)
            assertEquals(1, comments.size)
            assertEquals(1, requestCount)

            client.close()
        }

    @Test
    fun `listPullRequestReviewComments paginates across multiple pages`() =
        runTest {
            val page1Body = (1..100).joinToString(",", "[", "]") {
                """{"id": $it, "body": "review comment $it", "user": {"login": "user", "id": 1}, "path": "file.kt", "line": $it, "commit_id": "abc"}"""
            }
            val page2Body = (101..130).joinToString(",", "[", "]") {
                """{"id": $it, "body": "review comment $it", "user": {"login": "user", "id": 1}, "path": "file.kt", "line": $it, "commit_id": "abc"}"""
            }
            var requestCount = 0
            val mockEngine = MockEngine {
                requestCount++
                if (requestCount == 1) {
                    respond(
                        content = ByteReadChannel(page1Body),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            HttpHeaders.Link to listOf(
                                """<https://api.github.com/repos/owner/repo/pulls/1/comments?page=2&per_page=100>; rel="next""""
                            )
                        )
                    )
                } else {
                    respond(
                        content = ByteReadChannel(page2Body),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitHubClient(
                baseUrl = "https://api.github.com",
                token = "test-token",
                httpClient = httpClient
            )

            val comments = client.listPullRequestReviewComments("owner", "repo", 1)
            assertEquals(130, comments.size)
            assertEquals(2, requestCount)

            client.close()
        }

    @Test
    fun `isApprovedByCurrentUser returns false when fetchCurrentUsername fails`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"Unauthorized"}"""),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitHubClient(
                baseUrl = "https://api.github.com",
                token = "bad-token",
                httpClient = httpClient
            )

            assertFalse(client.isApprovedByCurrentUser("owner", "repo", 1))

            client.close()
        }
}
