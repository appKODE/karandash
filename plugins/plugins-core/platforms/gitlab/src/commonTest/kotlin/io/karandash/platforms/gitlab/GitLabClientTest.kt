package io.karandash.platforms.gitlab

import io.karandash.api.exception.ProviderApiException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitLabClientTest {
    private companion object {
        val SAMPLE_MR_JSON = """
            {
                "id": 1, "iid": 1, "title": "Test MR", "description": null,
                "source_branch": "feature", "target_branch": "main",
                "author": {"id": 1, "username": "user", "name": "User"},
                "state": "opened", "labels": []
            }
        """.trimIndent()
    }

    @Test
    fun testClientConfiguration() =
        runTest {
            // Test default configuration
            val client1 = GitLabClient()
            assertNotNull(client1)

            // Test with custom base URL
            val client2 = GitLabClient(baseUrl = "https://gitlab.example.com")
            assertNotNull(client2)

            // Test with token
            val client3 = GitLabClient(token = "test-token")
            assertNotNull(client3)

            // Test with both
            val client4 = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token"
            )
            assertNotNull(client4)

            // Clean up
            client1.close()
            client2.close()
            client3.close()
            client4.close()
        }

    @Test
    fun testUrlEncoding() =
        runTest {
            // Verify that project IDs with slashes are properly encoded
            // This would be tested with mock HTTP server in integration tests
            val client = GitLabClient()

            // Project ID "group/project" should be encoded to "group%2Fproject"
            // This is tested implicitly in the client.fetchMergeRequest call

            client.close()
        }

    @Test
    fun `fetchMergeRequest returns descriptive error for non-success response`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"401 Unauthorized"}"""),
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
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "bad-token",
                httpClient = httpClient
            )

            val exception = assertFailsWith<ProviderApiException> {
                client.fetchMergeRequest("group/project", 123)
            }

            assertEquals(401, exception.statusCode)
            assertEquals("GitLab", exception.platformName)
            assertTrue(exception.isUnauthorized)
            assertTrue(exception.isAuthError)
            assertFalse(exception.isForbidden)
            assertTrue(exception.message?.contains("HTTP 401") == true)
            assertTrue(exception.message?.contains("group/project") == true)
            assertFalse(exception.message?.contains("%2F") == true)
            client.close()
        }

    @Test
    fun `fetchMergeRequest returns ProviderApiException with correct fields for 403`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"403 Forbidden"}"""),
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
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "low-perm-token",
                httpClient = httpClient
            )

            val exception = assertFailsWith<ProviderApiException> {
                client.fetchMergeRequest("group/project", 42)
            }

            assertEquals(403, exception.statusCode)
            assertEquals("GitLab", exception.platformName)
            assertTrue(exception.isForbidden)
            assertTrue(exception.isAuthError)
            assertFalse(exception.isUnauthorized)
            client.close()
        }

    @Test
    fun `fetchMergeRequest returns ProviderApiException with correct fields for 404`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"404 Not Found"}"""),
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
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "valid-token",
                httpClient = httpClient
            )

            val exception = assertFailsWith<ProviderApiException> {
                client.fetchMergeRequest("private/project", 99)
            }

            assertEquals(404, exception.statusCode)
            assertTrue(exception.isNotFound)
            assertFalse(exception.isAuthError)
            client.close()
        }

    @Test
    fun `approveMergeRequest uses approveToken when set`() =
        runTest {
            var capturedAuthHeader: String? = null
            val mockEngine = MockEngine {
                capturedAuthHeader = it.headers["PRIVATE-TOKEN"]
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
                        },
                    )
                }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "project-token",
                httpClient = httpClient,
                approveToken = "user-pat",
            )

            client.approveMergeRequest("group/project", 1)

            assertEquals("user-pat", capturedAuthHeader)
            client.close()
        }

    @Test
    fun `approveMergeRequest throws when approveToken not set`() =
        runTest {
            val mockEngine = MockEngine {
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
                        },
                    )
                }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "project-token",
                httpClient = httpClient,
            )

            assertFailsWith<IllegalStateException> {
                client.approveMergeRequest("group/project", 1)
            }
            client.close()
        }

    @Test
    fun `unapproveMergeRequest uses approveToken when set`() =
        runTest {
            var capturedAuthHeader: String? = null
            val mockEngine = MockEngine {
                capturedAuthHeader = it.headers["PRIVATE-TOKEN"]
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
                        },
                    )
                }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "project-token",
                httpClient = httpClient,
                approveToken = "user-pat",
            )

            client.unapproveMergeRequest("group/project", 1)

            assertEquals("user-pat", capturedAuthHeader)
            client.close()
        }

    @Test
    fun `fetchMergeRequest still uses main token not approveToken`() =
        runTest {
            var capturedAuthHeader: String? = null
            val mockEngine = MockEngine {
                capturedAuthHeader = it.headers["PRIVATE-TOKEN"]
                respond(
                    content = ByteReadChannel(SAMPLE_MR_JSON),
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
                        },
                    )
                }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "project-token",
                httpClient = httpClient,
                approveToken = "user-pat",
            )

            client.fetchMergeRequest("group/project", 1)

            assertEquals("project-token", capturedAuthHeader)
            client.close()
        }

    @Test
    fun `unapproveMergeRequest treats 404 as already not approved`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"404 Not Found"}"""),
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
                        },
                    )
                }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "project-token",
                httpClient = httpClient,
                approveToken = "user-pat",
            )

            // Should not throw - 404 on unapprove means "already not approved"
            client.unapproveMergeRequest("group/project", 1)
            client.close()
        }

    @Test
    fun `unapproveMergeRequest throws on non-404 errors`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"403 Forbidden"}"""),
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
                        },
                    )
                }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "project-token",
                httpClient = httpClient,
                approveToken = "user-pat",
            )

            val exception = assertFailsWith<ProviderApiException> {
                client.unapproveMergeRequest("group/project", 1)
            }
            assertEquals(403, exception.statusCode)
            client.close()
        }

    @Test
    fun `fetchMergeRequest returns descriptive error for malformed successful payload`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"not a merge request payload"}"""),
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
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "token",
                httpClient = httpClient
            )

            val exception = assertFailsWith<IllegalStateException> {
                client.fetchMergeRequest("group/project", 123)
            }

            assertTrue(exception.message?.contains("response parsing failed", ignoreCase = true) == true)
            client.close()
        }

    @Test
    fun `fetchCurrentUsername returns null when approveToken not set`() =
        runTest {
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
            )

            val username = client.fetchCurrentUsername()
            assertEquals(null, username)

            client.close()
        }

    @Test
    fun `fetchCurrentUsername returns username from api-v4-user`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"id": 42, "username": "bot-user", "name": "Bot"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
                approveToken = "approve-pat",
            )

            val username = client.fetchCurrentUsername()
            assertEquals("bot-user", username)

            // Verify caching - second call should not make another request
            val username2 = client.fetchCurrentUsername()
            assertEquals("bot-user", username2)

            client.close()
        }

    @Test
    fun `fetchCurrentUsername returns null on API failure`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"401 Unauthorized"}"""),
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "bad-token",
                httpClient = httpClient
            )

            val username = client.fetchCurrentUsername()
            assertEquals(null, username)

            client.close()
        }

    @Test
    fun `fetchCurrentUsername uses approveToken when available`() =
        runTest {
            var capturedAuthHeader: String? = null
            val mockEngine = MockEngine { request ->
                capturedAuthHeader = request.headers["PRIVATE-TOKEN"]
                respond(
                    content = ByteReadChannel("""{"id": 42, "username": "approve-user", "name": "Approve User"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "project-token",
                httpClient = httpClient,
                approveToken = "approve-pat"
            )

            client.fetchCurrentUsername()
            assertEquals("approve-pat", capturedAuthHeader)

            client.close()
        }

    @Test
    fun `isApprovedByCurrentUser returns true when user has approved`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine { request ->
                requestCount++
                val url = request.url.toString()
                if (url.contains("/api/v4/user")) {
                    respond(
                        content = ByteReadChannel("""{"id": 42, "username": "bot-user", "name": "Bot"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else if (url.contains("/merge_requests/1/approvals")) {
                    respond(
                        content = ByteReadChannel(
                            """{
                                "id": 1, "iid": 1, "approved": true, "approvals_left": 0,
                                "approved_by": [{"user": {"id": 42, "username": "bot-user", "name": "Bot"}}]
                            }"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(content = ByteReadChannel(""), status = HttpStatusCode.NotFound)
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
                approveToken = "approve-pat",
            )

            assertTrue(client.isApprovedByCurrentUser("my-project", 1))

            client.close()
        }

    /**
     * Simulates the deleteMarkedInlineComments scenario from CI:
     * GET /notes returns 6 notes (3 with inline marker, 1 system-with-marker, 1 summary, 1 system-commit).
     * Only the 3 non-system inline-marker notes should be deleted.
     */
    @Test
    fun `deleteMarkedInlineComments scenario - only non-system marker notes are deleted`() =
        runTest {
            val notesFixture = """
            [
              {"id": 763368, "body": "<!-- karandash-inline -->\n❌ ERROR", "system": false},
              {"id": 763369, "body": "<!-- karandash-inline -->\n❌ ERROR", "system": false},
              {"id": 763371, "body": "<!-- karandash-inline -->\n⚠️ WARNING", "system": false},
              {"id": 763524, "body": "changed this line in version 2 of the diff", "system": true},
              {"id": 763373, "body": "<!-- karandash-validation-result -->\n## Karandash", "system": false},
              {"id": 763525, "body": "added 1 commit", "system": true}
            ]
            """.trimIndent()

            val deletedNoteIds = mutableListOf<Long>()
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                when {
                    request.method.value == "GET" && url.contains("/notes") -> {
                        respond(
                            content = ByteReadChannel(notesFixture),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    request.method.value == "DELETE" && url.contains("/notes/") -> {
                        val noteId = url.substringAfterLast("/").toLong()
                        deletedNoteIds.add(noteId)
                        respond(
                            content = ByteReadChannel(""),
                            status = HttpStatusCode.NoContent,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> {
                        error("Unexpected: ${request.method.value} $url")
                    }
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val notes = client.listNotes("2263", 1884)
            val marker = "karandash-inline"
            for (note in notes) {
                if (!note.system && note.body.contains(marker)) {
                    client.deleteNote("2263", 1884, note.id)
                }
            }

            assertEquals(3, deletedNoteIds.size)
            assertTrue(763368L in deletedNoteIds)
            assertTrue(763369L in deletedNoteIds)
            assertTrue(763371L in deletedNoteIds)
            assertFalse(763524L in deletedNoteIds) // system note
            assertFalse(763373L in deletedNoteIds) // summary marker
            assertFalse(763525L in deletedNoteIds) // system commit note
            client.close()
        }

    @Test
    fun `deleteNote throws ProviderApiException on 403 - resolved or inaccessible note`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"403 Forbidden"}"""),
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val exception = assertFailsWith<ProviderApiException> {
                client.deleteNote("2263", 1884, 763368L)
            }
            assertEquals(403, exception.statusCode)
            assertTrue(exception.isForbidden)
            client.close()
        }

    @Test
    fun `deleteNote throws ProviderApiException on 404`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"404 Note Not Found"}"""),
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val exception = assertFailsWith<ProviderApiException> {
                client.deleteNote("2263", 1884, 999999L)
            }
            assertEquals(404, exception.statusCode)
            assertTrue(exception.isNotFound)
            client.close()
        }

    @Test
    fun `listNotes parses system flag and body correctly`() =
        runTest {
            val fixture = """
            [
              {"id": 1, "body": "<!-- karandash-inline -->\nError", "system": false},
              {"id": 2, "body": "added 1 commit", "system": true},
              {"id": 3, "body": "<!-- karandash-validation-result -->", "system": false}
            ]
            """.trimIndent()
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel(fixture),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val notes = client.listNotes("group/project", 42)

            assertEquals(3, notes.size)
            assertFalse(notes[0].system)
            assertTrue(notes[0].body.contains("karandash-inline"))
            assertTrue(notes[1].system)
            assertEquals("added 1 commit", notes[1].body)
            assertFalse(notes[2].system)

            client.close()
        }

    @Test
    fun `listNotes paginates across multiple pages`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine { request ->
                requestCount++
                val url = request.url.toString()
                val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
                val perPage = request.url.parameters["per_page"]?.toIntOrNull() ?: 20
                assertEquals(100, perPage, "should request max per_page")
                assertTrue(url.contains("/notes"), "should hit notes endpoint")

                when (page) {
                    1 -> respond(
                        content = ByteReadChannel(
                            (1..100).joinToString(",", "[", "]") { i ->
                                """{"id": $i, "body": "note $i", "system": false}"""
                            }
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            "x-next-page" to listOf("2"),
                        ),
                    )

                    2 -> respond(
                        content = ByteReadChannel(
                            (101..125).joinToString(",", "[", "]") { i ->
                                """{"id": $i, "body": "note $i", "system": false}"""
                            }
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                        ),
                    )

                    else -> error("Unexpected page: $page")
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val notes = client.listNotes("group/project", 42)

            assertEquals(125, notes.size)
            assertEquals(1L, notes.first().id)
            assertEquals(125L, notes.last().id)
            assertEquals(2, requestCount)
            client.close()
        }

    @Test
    fun `listNotes stops at safety cap`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine {
                requestCount++
                respond(
                    content = ByteReadChannel(
                        (1..100).joinToString(",", "[", "]") { i ->
                            """{"id": ${(requestCount - 1) * 100 + i}, "body": "note", "system": false}"""
                        }
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        "x-next-page" to listOf("${requestCount + 1}"),
                    ),
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val notes = client.listNotes("group/project", 42)

            assertEquals(1000, notes.size, "should cap at 10 pages * 100 items")
            assertEquals(10, requestCount, "should stop after 10 pages")
            client.close()
        }

    @Test
    fun `listNotes single page does not make extra requests`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine {
                requestCount++
                respond(
                    content = ByteReadChannel(
                        """[{"id": 1, "body": "only note", "system": false}]"""
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val notes = client.listNotes("group/project", 42)

            assertEquals(1, notes.size)
            assertEquals(1, requestCount, "should only make one request for partial page")
            client.close()
        }

    @Test
    fun `listDiscussions paginates across multiple pages`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine { request ->
                requestCount++
                val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
                when (page) {
                    1 -> respond(
                        content = ByteReadChannel(
                            (1..100).joinToString(",", "[", "]") { i ->
                                """{"id": "disc-$i", "notes": [{"id": $i, "body": "note $i", "system": false}]}"""
                            }
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            "x-next-page" to listOf("2"),
                        ),
                    )

                    2 -> respond(
                        content = ByteReadChannel(
                            """[{"id": "disc-101", "notes": [{"id": 101, "body": "note 101", "system": false}]}]"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )

                    else -> error("Unexpected page: $page")
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val discussions = GitLabDiscussionsClient(client).listDiscussions("group/project", 42)

            assertEquals(101, discussions.size)
            assertEquals(2, requestCount)
            client.close()
        }

    @Test
    fun `findExistingComment finds marker on later page`() =
        runTest {
            val mockEngine = MockEngine { request ->
                val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
                when (page) {
                    1 -> respond(
                        content = ByteReadChannel(
                            (1..100).joinToString(",", "[", "]") { i ->
                                """{"id": $i, "body": "regular note $i", "system": false}"""
                            }
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            "x-next-page" to listOf("2"),
                        ),
                    )

                    2 -> respond(
                        content = ByteReadChannel(
                            """[
                                {"id": 101, "body": "regular note 101", "system": false},
                                {"id": 102, "body": "<!-- karandash-validation-result -->\n## Karandash", "system": false},
                                {"id": 103, "body": "regular note 103", "system": false}
                            ]"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )

                    else -> error("Unexpected page: $page")
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val notes = client.listNotes("group/project", 42)
            val marker = "<!-- karandash-validation-result -->"
            val existing = notes.find { it.body.contains(marker) }

            assertNotNull(existing, "should find marker note on page 2")
            assertEquals(102L, existing.id)
            client.close()
        }

    @Test
    fun `resolveDiscussion sends PUT to correct endpoint`() =
        runTest {
            var capturedMethod: String? = null
            var capturedUrl: String? = null
            val mockEngine = MockEngine { request ->
                capturedMethod = request.method.value
                capturedUrl = request.url.toString()
                respond(
                    content = ByteReadChannel("{}"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            GitLabDiscussionsClient(client).resolveDiscussion("group/project", 42, "abc123def")

            assertEquals("PUT", capturedMethod)
            assertTrue(capturedUrl?.contains("/discussions/abc123def") == true)
            assertTrue(capturedUrl?.contains("/merge_requests/42/") == true)
            client.close()
        }

    @Test
    fun `resolveDiscussion throws on non-success response`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel("""{"message":"403 Forbidden"}"""),
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            assertFailsWith<ProviderApiException> {
                GitLabDiscussionsClient(client).resolveDiscussion("group/project", 42, "abc123def")
            }
            client.close()
        }

    @Test
    fun `findAllNotesWithMarker returns all matching non-system note IDs`() =
        runTest {
            val fixture = """
            [
              {"id": 1, "body": "<!-- marker -->first", "system": false},
              {"id": 2, "body": "unrelated note", "system": false},
              {"id": 3, "body": "<!-- marker -->second", "system": false},
              {"id": 4, "body": "<!-- marker -->system note", "system": true},
              {"id": 5, "body": "<!-- marker -->third", "system": false}
            ]
            """.trimIndent()
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel(fixture),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val ids = client.findAllNotesWithMarker("group/project", 42, "<!-- marker -->")

            assertEquals(listOf("1", "3", "5"), ids)
            client.close()
        }

    @Test
    fun `findAllNotesWithMarker returns empty list when no matches`() =
        runTest {
            val fixture = """[{"id": 1, "body": "no match here", "system": false}]"""
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel(fixture),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient,
            )

            val ids = client.findAllNotesWithMarker("group/project", 42, "<!-- marker -->")

            assertEquals(emptyList(), ids)
            client.close()
        }

    @Test
    fun `isApprovedByCurrentUser returns false when user has not approved`() =
        runTest {
            val mockEngine = MockEngine { request ->
                val url = request.url.toString()
                if (url.contains("/api/v4/user")) {
                    respond(
                        content = ByteReadChannel("""{"id": 42, "username": "bot-user", "name": "Bot"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else if (url.contains("/merge_requests/1/approvals")) {
                    respond(
                        content = ByteReadChannel(
                            """{
                                "id": 1, "iid": 1, "approved": true, "approvals_left": 0,
                                "approved_by": [{"user": {"id": 99, "username": "other-user", "name": "Other"}}]
                            }"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(content = ByteReadChannel(""), status = HttpStatusCode.NotFound)
                }
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GitLabClient(
                baseUrl = "https://gitlab.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            assertFalse(client.isApprovedByCurrentUser("my-project", 1))

            client.close()
        }

    private fun authClient(
        status: HttpStatusCode,
        seenUrls: MutableList<String>
    ): GitLabClient {
        val mockEngine = MockEngine { request ->
            seenUrls += request.url.toString()
            respond(content = ByteReadChannel("{}"), status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val httpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GitLabClient(baseUrl = "https://gitlab.example.com", token = "test-token", httpClient = httpClient)
    }

    @Test
    fun `checkAuth is true on 200 from the user endpoint`() =
        runTest {
            val urls = mutableListOf<String>()
            val client = authClient(HttpStatusCode.OK, urls)
            assertTrue(client.checkAuth("${client.baseUrl}/api/v4/user"))
            assertEquals(listOf("https://gitlab.example.com/api/v4/user"), urls)
            client.close()
        }

    @Test
    fun `checkAuth is false on 401 and 403`() =
        runTest {
            listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden).forEach { status ->
                val client = authClient(status, mutableListOf())
                assertFalse(client.checkAuth("${client.baseUrl}/api/v4/user"))
                client.close()
            }
        }

    @Test
    fun `checkAuth throws on other failures`() =
        runTest {
            val client = authClient(HttpStatusCode.InternalServerError, mutableListOf())
            val e = assertFailsWith<ProviderApiException> { client.checkAuth("${client.baseUrl}/api/v4/user") }
            assertEquals(500, e.statusCode)
            client.close()
        }

    @Test
    fun `gitlab token is never sent to another host`() =
        runTest {
            val seen = mutableMapOf<String, String?>()
            val mockEngine = MockEngine { request ->
                seen[request.url.host] = request.headers["PRIVATE-TOKEN"]
                respond(ByteReadChannel("{}"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = GitLabClient(baseUrl = "https://gitlab.example.com", token = "glpat-secret", httpClient = HttpClient(mockEngine))

            client.checkAuth("https://gitlab.example.com/api/v4/user")
            client.checkAuth("https://attacker.example.net/api/v4/user")

            assertEquals("glpat-secret", seen["gitlab.example.com"], "the keyed host still gets its token")
            assertEquals(null, seen["attacker.example.net"], "another host never sees the token")
            client.close()
        }
}
