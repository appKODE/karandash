package io.karandash.platforms.bitbucket

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BitbucketClientTest {
    @Test
    fun testClientCreation() =
        runTest {
            val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = BitbucketClient(httpClient = httpClient)
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testClientWithToken() =
        runTest {
            val mockEngine = MockEngine { respond("", HttpStatusCode.OK) }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = BitbucketClient(httpClient = httpClient, token = "test-token")
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testListComments() =
        runTest {
            val mockEngine = MockEngine { request ->
                assertEquals("/2.0/repositories/test-workspace/test-repo/pullrequests/1/comments", request.url.encodedPath)
                respond(
                    content = """
                {
                    "pagelen": 10,
                    "values": [
                        {
                            "id": 123,
                            "content": {
                                "raw": "First comment",
                                "markup": "markdown",
                                "html": "<p>First comment</p>"
                            },
                            "user": {
                                "uuid": "{abc-123}",
                                "username": "user1",
                                "display_name": "User One"
                            },
                            "created_on": "2024-01-01T10:00:00Z",
                            "updated_on": "2024-01-01T10:00:00Z"
                        },
                        {
                            "id": 456,
                            "content": {
                                "raw": "Second comment",
                                "markup": "markdown",
                                "html": "<p>Second comment</p>"
                            },
                            "user": {
                                "uuid": "{def-456}",
                                "username": "user2",
                                "display_name": "User Two"
                            },
                            "created_on": "2024-01-02T10:00:00Z",
                            "updated_on": "2024-01-02T10:00:00Z"
                        }
                    ],
                    "page": 1,
                    "size": 2
                }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val mockHttpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = BitbucketClient(
                httpClient = mockHttpClient,
                token = "test-token"
            )

            val comments = client.listComments("test-workspace", "test-repo", 1)
            assertEquals(2, comments.size)
            assertEquals(123L, comments[0].id)
            assertEquals("First comment", comments[0].content?.raw)
            assertEquals(456L, comments[1].id)
            assertEquals("Second comment", comments[1].content?.raw)

            client.close()
        }

    @Test
    fun `listComments paginates across multiple pages`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine { request ->
                requestCount++
                val url = request.url.toString()
                if (url.contains("page=2")) {
                    respond(
                        content = """
                        {
                            "pagelen": 100,
                            "values": [
                                {
                                    "id": 2,
                                    "content": {"raw": "Page 2 comment"},
                                    "user": {"uuid": "{uuid-2}", "display_name": "user2"}
                                }
                            ],
                            "page": 2,
                            "size": 2
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        content = """
                        {
                            "pagelen": 100,
                            "values": [
                                {
                                    "id": 1,
                                    "content": {"raw": "Page 1 comment"},
                                    "user": {"uuid": "{uuid-1}", "display_name": "user1"}
                                }
                            ],
                            "page": 1,
                            "size": 2,
                            "next": "https://api.bitbucket.org/2.0/repositories/ws/repo/pullrequests/1/comments?pagelen=100&page=2"
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }

            val mockHttpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = BitbucketClient(httpClient = mockHttpClient, token = "test-token")

            val comments = client.listComments("ws", "repo", 1)
            assertEquals(2, comments.size)
            assertEquals(1L, comments[0].id)
            assertEquals("Page 1 comment", comments[0].content?.raw)
            assertEquals(2L, comments[1].id)
            assertEquals("Page 2 comment", comments[1].content?.raw)
            assertEquals(2, requestCount)

            client.close()
        }

    @Test
    fun testListCommentsEmpty() =
        runTest {
            val mockEngine = MockEngine { request ->
                assertEquals("/2.0/repositories/test-workspace/test-repo/pullrequests/1/comments", request.url.encodedPath)
                respond(
                    content = """
                {
                    "pagelen": 10,
                    "values": [],
                    "page": 1,
                    "size": 0
                }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val mockHttpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = BitbucketClient(httpClient = mockHttpClient, token = "test-token")

            val comments = client.listComments("test-workspace", "test-repo", 1)
            assertEquals(0, comments.size)

            client.close()
        }

    @Test
    fun testUpdateComment() =
        runTest {
            val mockEngine = MockEngine { request ->
                assertEquals("/2.0/repositories/test-workspace/test-repo/pullrequests/1/comments/123", request.url.encodedPath)
                assertEquals("PUT", request.method.value)
                respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val mockHttpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = BitbucketClient(httpClient = mockHttpClient, token = "test-token")

            client.updateComment("test-workspace", "test-repo", 1, 123L, "Updated comment content")

            client.close()
        }

    @Test
    fun testPostComment() =
        runTest {
            val mockEngine = MockEngine { request ->
                assertEquals("/2.0/repositories/test-workspace/test-repo/pullrequests/1/comments", request.url.encodedPath)
                assertEquals("POST", request.method.value)
                respond(
                    content = """
                {
                    "id": 789,
                    "content": {
                        "raw": "New comment",
                        "markup": "markdown",
                        "html": "<p>New comment</p>"
                    },
                    "user": {
                        "uuid": "{ghi-789}",
                        "username": "user3",
                        "display_name": "User Three"
                    },
                    "created_on": "2024-01-03T10:00:00Z",
                    "updated_on": "2024-01-03T10:00:00Z"
                }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }

            val mockHttpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }

            val client = BitbucketClient(httpClient = mockHttpClient, token = "test-token")

            val comment = client.postComment("test-workspace", "test-repo", 1, "New comment")
            assertEquals(789L, comment.id)
            assertEquals("New comment", comment.content?.raw)

            client.close()
        }

    @Test
    fun `fetchCurrentUserUuid returns uuid from user endpoint`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"uuid": "{abc-123}", "display_name": "Bot"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = BitbucketClient(httpClient = httpClient, token = "test-token")

            assertEquals("{abc-123}", client.fetchCurrentUserUuid())

            client.close()
        }

    @Test
    fun `fetchCurrentUserUuid returns null on API failure`() =
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
            val client = BitbucketClient(httpClient = httpClient, token = "bad-token")

            assertEquals(null, client.fetchCurrentUserUuid())

            client.close()
        }

    @Test
    fun `isApprovedByCurrentUser returns true when user has approved`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine { request ->
                requestCount++
                val url = request.url.toString()
                if (url.endsWith("/user")) {
                    respond(
                        content = ByteReadChannel("""{"uuid": "{abc-123}", "display_name": "Bot"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        content = ByteReadChannel(
                            """{
                            "id": 1, "title": "Test PR", "state": "OPEN",
                            "author": {"uuid": "{author-uuid}", "display_name": "Author"},
                            "source": {"branch": {"name": "feature"}, "commit": {"hash": "abc"}, "repository": {"uuid": "{r}", "name": "repo", "full_name": "ws/repo"}},
                            "destination": {"branch": {"name": "main"}, "commit": {"hash": "def"}, "repository": {"uuid": "{r}", "name": "repo", "full_name": "ws/repo"}},
                            "created_on": "2024-01-01T00:00:00Z", "updated_on": "2024-01-01T00:00:00Z",
                            "participants": [
                                {"user": {"uuid": "{abc-123}", "display_name": "Bot"}, "role": "REVIEWER", "approved": true}
                            ]
                        }"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = BitbucketClient(httpClient = httpClient, token = "test-token")

            assertTrue(client.isApprovedByCurrentUser("workspace", "repo", 1))

            client.close()
        }

    @Test
    fun `isApprovedByCurrentUser returns false when user has not approved`() =
        runTest {
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                if (url.endsWith("/user")) {
                    respond(
                        content = ByteReadChannel("""{"uuid": "{abc-123}", "display_name": "Bot"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        content = ByteReadChannel(
                            """{
                            "id": 1, "title": "Test PR", "state": "OPEN",
                            "author": {"uuid": "{author-uuid}", "display_name": "Author"},
                            "source": {"branch": {"name": "feature"}, "commit": {"hash": "abc"}, "repository": {"uuid": "{r}", "name": "repo", "full_name": "ws/repo"}},
                            "destination": {"branch": {"name": "main"}, "commit": {"hash": "def"}, "repository": {"uuid": "{r}", "name": "repo", "full_name": "ws/repo"}},
                            "created_on": "2024-01-01T00:00:00Z", "updated_on": "2024-01-01T00:00:00Z",
                            "participants": [
                                {"user": {"uuid": "{other-uuid}", "display_name": "Other"}, "role": "REVIEWER", "approved": true}
                            ]
                        }"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = BitbucketClient(httpClient = httpClient, token = "test-token")

            assertFalse(client.isApprovedByCurrentUser("workspace", "repo", 1))

            client.close()
        }
}
