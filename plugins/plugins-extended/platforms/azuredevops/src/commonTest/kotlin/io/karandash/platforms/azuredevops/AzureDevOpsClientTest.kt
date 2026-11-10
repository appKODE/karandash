package io.karandash.platforms.azuredevops

import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AzureDevOpsClientTest {
    @Test
    fun testClientCreation() =
        runTest {
            val client = AzureDevOpsClient()
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testClientWithCustomBaseUrl() =
        runTest {
            val client = AzureDevOpsClient(baseUrl = "https://dev.azure.company.com")
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testClientWithToken() =
        runTest {
            val client = AzureDevOpsClient(token = "test-token")
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testClientWithAllParameters() =
        runTest {
            val client = AzureDevOpsClient(
                baseUrl = "https://dev.azure.company.com",
                token = "test-token"
            )
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testListThreads() =
        runTest {
            val mockEngine = MockEngine { request ->
                when {
                    request.url.encodedPath.contains("/threads") -> {
                        respond(
                            content = ByteReadChannel(
                                """
                            {
                              "value": [
                                {
                                  "id": 1,
                                  "comments": [
                                    {
                                      "id": 10,
                                      "content": "First comment",
                                      "publishedDate": "2024-01-01T10:00:00Z"
                                    }
                                  ],
                                  "status": "active",
                                  "publishedDate": "2024-01-01T10:00:00Z"
                                },
                                {
                                  "id": 2,
                                  "comments": [
                                    {
                                      "id": 20,
                                      "content": "Second comment",
                                      "publishedDate": "2024-01-02T10:00:00Z"
                                    }
                                  ],
                                  "status": "closed",
                                  "publishedDate": "2024-01-02T10:00:00Z"
                                }
                              ],
                              "count": 2
                            }
                                """.trimIndent()
                            ),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }

                    else -> {
                        error("Unhandled ${request.url.encodedPath}")
                    }
                }
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

            val client = AzureDevOpsClient("https://dev.azure.com", "test-token", httpClient)
            val response = client.listThreads("myorg", "myproject", "repo-id", 123)

            assertEquals(2, response.count)
            assertEquals(2, response.value.size)
            assertEquals(1, response.value[0].id)
            assertEquals("active", response.value[0].status)
            assertEquals(1, response.value[0].comments.size)
            assertEquals("First comment", response.value[0].comments[0].content)

            client.close()
        }

    @Test
    fun testUpdateThread() =
        runTest {
            var capturedUrl = ""
            var capturedMethod = ""

            val mockEngine = MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method.value

                respond(
                    content = ByteReadChannel("{}"),
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

            val client = AzureDevOpsClient("https://dev.azure.com", "test-token", httpClient)
            client.updateThread("myorg", "myproject", "repo-id", 123, 456, 789, "updated content")

            assertTrue(capturedUrl.contains("/threads/456/comments/789"))
            // Azure's "Pull Request Thread Comments - Update" is a PATCH; PUT is rejected
            assertEquals("PATCH", capturedMethod)

            client.close()
        }

    @Test
    fun testAddLabel() =
        runTest {
            var capturedUrl = ""
            var capturedMethod = ""

            val mockEngine = MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method.value

                respond(
                    content = ByteReadChannel(
                        """
                        {
                          "id": "label-123",
                          "name": "bug",
                          "active": true
                        }
                        """.trimIndent()
                    ),
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

            val client = AzureDevOpsClient("https://dev.azure.com", "test-token", httpClient)
            client.addLabel("myorg", "myproject", "repo-id", 123, "bug")

            assertTrue(capturedUrl.contains("/pullrequests/123/labels"))
            assertEquals("POST", capturedMethod)

            client.close()
        }

    @Test
    fun testRemoveLabel() =
        runTest {
            var capturedUrl = ""
            var capturedMethod = ""

            val mockEngine = MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method.value

                respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.NoContent,
                    headers = headersOf()
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

            val client = AzureDevOpsClient("https://dev.azure.com", "test-token", httpClient)
            client.removeLabel("myorg", "myproject", "repo-id", 123, "label-456")

            assertTrue(capturedUrl.contains("/pullrequests/123/labels/label-456"))
            assertEquals("DELETE", capturedMethod)

            client.close()
        }

    @Test
    fun testListThreadsEmptyResponse() =
        runTest {
            val mockEngine = MockEngine { request ->
                respond(
                    content = ByteReadChannel(
                        """
                        {
                          "value": [],
                          "count": 0
                        }
                        """.trimIndent()
                    ),
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

            val client = AzureDevOpsClient("https://dev.azure.com", "test-token", httpClient)
            val response = client.listThreads("myorg", "myproject", "repo-id", 123)

            assertEquals(0, response.count)
            assertEquals(0, response.value.size)

            client.close()
        }

    @Test
    fun testUpdateThreadSendsCorrectBody() =
        runTest {
            var capturedContentType: ContentType? = null

            val mockEngine = MockEngine { request ->
                capturedContentType = request.body.contentType

                respond(
                    content = ByteReadChannel("{}"),
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

            val client = AzureDevOpsClient("https://dev.azure.com", "test-token", httpClient)
            client.updateThread("org", "proj", "repo", 1, 2, 3, "new content")

            assertEquals(ContentType.Application.Json, capturedContentType)

            client.close()
        }

    @Test
    fun testAddLabelSendsCorrectBody() =
        runTest {
            var capturedContentType: ContentType? = null

            val mockEngine = MockEngine { request ->
                capturedContentType = request.body.contentType

                respond(
                    content = ByteReadChannel("""{"id":"1","name":"test","active":true}"""),
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

            val client = AzureDevOpsClient("https://dev.azure.com", "test-token", httpClient)
            client.addLabel("org", "proj", "repo", 1, "test")

            assertEquals(ContentType.Application.Json, capturedContentType)

            client.close()
        }

    @Test
    fun `getCurrentUserVote returns vote value`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"vote": 10}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = AzureDevOpsClient(
                token = "test-token",
                httpClient = httpClient
            )

            assertEquals(10, client.getCurrentUserVote("org", "project", "repo", 1))

            client.close()
        }

    @Test
    fun `getCurrentUserVote returns null on API failure`() =
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
            val client = AzureDevOpsClient(
                token = "bad-token",
                httpClient = httpClient
            )

            assertEquals(null, client.getCurrentUserVote("org", "project", "repo", 1))

            client.close()
        }

    @Test
    fun `getCurrentUserVote returns zero for no vote`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"vote": 0}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = AzureDevOpsClient(
                token = "test-token",
                httpClient = httpClient
            )

            assertEquals(0, client.getCurrentUserVote("org", "project", "repo", 1))

            client.close()
        }
}
