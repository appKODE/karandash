package io.karandash.platforms.gitea

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GiteaClientTest {
    private val baseUrl = "https://gitea.example.com"

    @Test
    fun testClientCreation() =
        runTest {
            val client = GiteaClient(baseUrl = "https://gitea.example.com")
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testClientWithToken() =
        runTest {
            val client = GiteaClient(
                baseUrl = "https://gitea.example.com",
                token = "test-token-12345"
            )
            assertNotNull(client)
            assertTrue(client.hasToken())
            client.close()
        }

    @Test
    fun testClientWithoutToken() =
        runTest {
            val client = GiteaClient(baseUrl = "https://gitea.example.com")
            assertNotNull(client)
            assertFalse(client.hasToken())
            client.close()
        }

    @Test
    fun testGetBaseUrl() =
        runTest {
            val baseUrl = "https://gitea.org"
            val client = GiteaClient(baseUrl = baseUrl)
            assertNotNull(client.baseUrl)
            client.close()
        }

    @Test
    fun testCustomGiteaInstance() =
        runTest {
            val client = GiteaClient(
                baseUrl = "https://internal.gitea.company.com",
                token = "secret-token"
            )
            assertNotNull(client)
            assertTrue(client.hasToken())
            client.close()
        }

    @Test
    fun testMultipleClientsWithDifferentUrls() =
        runTest {
            val client1 = GiteaClient(baseUrl = "https://gitea1.example.com")
            val client2 = GiteaClient(baseUrl = "https://gitea2.example.com")

            assertNotNull(client1)
            assertNotNull(client2)

            client1.close()
            client2.close()
        }

    @Test
    fun `postComment sends POST to correct URL with token header`() =
        runTest {
            var capturedUrl = ""
            var capturedToken = ""

            val mockEngine = MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedToken = request.headers[HttpHeaders.Authorization] ?: ""

                respond(
                    content = """{"id": 123, "body": "test comment"}""",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "my-token", httpClient)

            val comment = client.postComment("owner", "repo", 456, "test comment")

            assertTrue(capturedUrl.contains("/api/v1/repos/owner/repo/issues/456/comments"))
            assertEquals("token my-token", capturedToken)
            assertEquals(123L, comment.id)
            assertEquals("test comment", comment.body)

            client.close()
        }

    @Test
    fun `listComments returns list of comments`() =
        runTest {
            val mockEngine = MockEngine { request ->
                respond(
                    content = """[
                    {"id": 1, "body": "First comment"},
                    {"id": 2, "body": "Second comment"},
                    {"id": 3, "body": "Third comment"}
                ]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "token", httpClient)

            val comments = client.listComments("owner", "repo", 123)

            assertEquals(3, comments.size)
            assertEquals(1L, comments[0].id)
            assertEquals("First comment", comments[0].body)
            assertEquals(2L, comments[1].id)
            assertEquals("Second comment", comments[1].body)

            client.close()
        }

    @Test
    fun `updateComment sends PATCH to correct URL`() =
        runTest {
            var capturedUrl = ""
            var capturedMethod = ""

            val mockEngine = MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method.value

                respond(
                    content = """{"id": 789, "body": "updated comment"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "my-token", httpClient)

            client.updateComment("owner", "repo", 789L, "updated comment")

            assertTrue(capturedUrl.contains("/api/v1/repos/owner/repo/issues/comments/789"))
            assertEquals("PATCH", capturedMethod)

            client.close()
        }

    @Test
    fun `addLabels sends POST with label IDs`() =
        runTest {
            var capturedUrl = ""

            val mockEngine = MockEngine { request ->
                capturedUrl = request.url.toString()

                respond(
                    content = """[]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "token", httpClient)

            client.addLabels("owner", "repo", 123, listOf(1L, 2L, 3L))

            assertTrue(capturedUrl.contains("/api/v1/repos/owner/repo/issues/123/labels"))

            client.close()
        }

    @Test
    fun `removeLabel sends DELETE to correct URL`() =
        runTest {
            var capturedUrl = ""
            var capturedMethod = ""

            val mockEngine = MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method.value

                respond(
                    content = """{}""",
                    status = HttpStatusCode.NoContent,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "token", httpClient)

            client.removeLabel("owner", "repo", 123, 456L)

            assertTrue(capturedUrl.contains("/api/v1/repos/owner/repo/issues/123/labels/456"))
            assertEquals("DELETE", capturedMethod)

            client.close()
        }

    @Test
    fun `editPullRequest sends PATCH to pulls endpoint`() =
        runTest {
            var capturedUrl = ""
            var capturedMethod = ""

            val mockEngine = MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method.value

                respond(
                    content = """{"id": 1, "title": "Updated title"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "token", httpClient)

            client.editPullRequest("owner", "repo", 123, buildJsonObject { put("title", "Updated title") })

            assertTrue(capturedUrl.contains("/api/v1/repos/owner/repo/pulls/123"))
            assertEquals("PATCH", capturedMethod)

            client.close()
        }

    @Test
    fun `postComment handles 401 unauthorized`() =
        runTest {
            val mockEngine = MockEngine { request ->
                respond(
                    content = ByteArray(0),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "invalid-token", httpClient)

            val exception = assertFailsWith<Exception> {
                client.postComment("owner", "repo", 123, "test")
            }
            assertTrue(
                exception is io.karandash.api.exception.ProviderApiException,
                "Expected ProviderApiException, got ${exception::class.simpleName}"
            )

            client.close()
        }

    @Test
    fun `updateComment handles 404 not found`() =
        runTest {
            val mockEngine = MockEngine { request ->
                respond(
                    content = ByteArray(0),
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "token", httpClient)

            assertFailsWith<Exception> {
                client.updateComment("owner", "repo", 999L, "updated")
            }

            client.close()
        }

    @Test
    fun `addLabels handles 500 server error`() =
        runTest {
            val mockEngine = MockEngine { request ->
                respond(
                    content = ByteArray(0),
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "token", httpClient)

            assertFailsWith<Exception> {
                client.addLabels("owner", "repo", 123, listOf(1L))
            }

            client.close()
        }

    @Test
    fun `removeLabel handles 403 forbidden`() =
        runTest {
            val mockEngine = MockEngine { request ->
                respond(
                    content = ByteArray(0),
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "token", httpClient)

            assertFailsWith<Exception> {
                client.removeLabel("owner", "repo", 123, 456L)
            }

            client.close()
        }

    @Test
    fun `editPullRequest handles empty updates map`() =
        runTest {
            val mockEngine = MockEngine { request ->
                respond(
                    content = """{"id": 1}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "token", httpClient)

            client.editPullRequest("owner", "repo", 123, JsonObject(emptyMap()))

            client.close()
        }

    @Test
    fun `listComments returns empty list when no comments`() =
        runTest {
            val mockEngine = MockEngine { request ->
                respond(
                    content = """[]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = GiteaClient(baseUrl, "token", httpClient)

            val comments = client.listComments("owner", "repo", 123)

            assertEquals(0, comments.size)

            client.close()
        }

    @Test
    fun `fetchCurrentUsername returns login from user endpoint`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"id": 42, "login": "bot-user"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GiteaClient(
                baseUrl = "https://gitea.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            assertEquals("bot-user", client.fetchCurrentUsername())
            // Verify caching
            assertEquals("bot-user", client.fetchCurrentUsername())

            client.close()
        }

    @Test
    fun `fetchCurrentUsername returns null on API failure`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GiteaClient(
                baseUrl = "https://gitea.example.com",
                token = "bad-token",
                httpClient = httpClient
            )

            assertEquals(null, client.fetchCurrentUsername())

            client.close()
        }

    @Test
    fun `isApprovedByCurrentUser returns true when user has approved`() =
        runTest {
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                if (url.endsWith("/api/v1/user")) {
                    respond(
                        content = ByteReadChannel("""{"id": 42, "login": "bot-user"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        content = ByteReadChannel(
                            """[
                            {"id": 1, "state": "APPROVED", "user": {"id": 42, "login": "bot-user"}}
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
            val client = GiteaClient(
                baseUrl = "https://gitea.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            assertTrue(client.isApprovedByCurrentUser("owner", "repo", 1))

            client.close()
        }

    @Test
    fun `listComments single page makes no extra requests`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine { _ ->
                requestCount++
                respond(
                    content = """[{"id": 1, "body": "a"}, {"id": 2, "body": "b"}, {"id": 3, "body": "c"}]""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val client = GiteaClient(baseUrl, "token", httpClient)

            val comments = client.listComments("owner", "repo", 1)

            assertEquals(3, comments.size)
            assertEquals(1, requestCount)

            client.close()
        }

    @Test
    fun `listPullReviewComments reads the comments of every review`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine { request ->
                requestCount++
                val path = request.url.encodedPath
                val content = when {
                    path.endsWith("/pulls/1/reviews") -> """[{"id": 7}, {"id": 8}]"""
                    path.endsWith("/reviews/7/comments") -> """[{"id": 1, "body": "a"}, {"id": 2, "body": "b"}]"""
                    else -> """[{"id": 3, "body": "c"}]"""
                }
                respond(
                    content = content,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
            val client = GiteaClient(baseUrl, "token", httpClient)

            val reviewComments = client.listPullReviewComments("owner", "repo", 1)

            assertEquals(listOf(1L, 2L, 3L), reviewComments.map { it.id })
            assertEquals(3, requestCount)

            client.close()
        }

    @Test
    fun `isApprovedByCurrentUser returns false when user has not approved`() =
        runTest {
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                if (url.endsWith("/api/v1/user")) {
                    respond(
                        content = ByteReadChannel("""{"id": 42, "login": "bot-user"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        content = ByteReadChannel(
                            """[
                            {"id": 1, "state": "APPROVED", "user": {"id": 99, "login": "other-user"}}
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
            val client = GiteaClient(
                baseUrl = "https://gitea.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            assertFalse(client.isApprovedByCurrentUser("owner", "repo", 1))

            client.close()
        }
}
