package io.karandash.platforms.gerrit

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

class GerritClientTest {
    @Test
    fun testClientCreation() =
        runTest {
            val client = GerritClient(baseUrl = "https://gerrit.example.com")
            assertNotNull(client)
            client.close()
        }

    @Test
    fun testClientWithToken() =
        runTest {
            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "test-token-xyz"
            )
            assertNotNull(client)
            assertTrue(client.hasToken())
            client.close()
        }

    @Test
    fun testClientWithoutToken() =
        runTest {
            val client = GerritClient(baseUrl = "https://gerrit.example.com")
            assertNotNull(client)
            assertFalse(client.hasToken())
            client.close()
        }

    @Test
    fun testGetBaseUrl() =
        runTest {
            val baseUrl = "https://gerrit.company.com"
            val client = GerritClient(baseUrl = baseUrl)
            assertNotNull(client.baseUrl)
            client.close()
        }

    @Test
    fun testCustomGerritInstance() =
        runTest {
            val client = GerritClient(
                baseUrl = "https://internal.gerrit.org",
                token = "secret-gerrit-token"
            )
            assertNotNull(client)
            assertTrue(client.hasToken())
            client.close()
        }

    @Test
    fun testMultipleClientsWithDifferentUrls() =
        runTest {
            val client1 = GerritClient(baseUrl = "https://gerrit1.example.com")
            val client2 = GerritClient(baseUrl = "https://gerrit2.example.com")

            assertNotNull(client1)
            assertNotNull(client2)

            client1.close()
            client2.close()
        }

    @Test
    fun testGerritWithBasicAuth() =
        runTest {
            // Test configuration for HTTP Basic Auth pattern
            val token = "password-token"
            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = token
            )

            assertNotNull(client)
            assertTrue(client.hasToken())
            client.close()
        }

    @Test
    fun testGetChange() =
        runTest {
            val mockEngine = MockEngine { request ->
                assertEquals(
                    "https://gerrit.example.com/changes/project~123?o=CURRENT_REVISION&o=LABELS&o=MESSAGES",
                    request.url.toString()
                )
                respond(
                    content = """)]}'
{
  "id": "project~master~I1234567890abcdef",
  "project": "project",
  "branch": "master",
  "change_id": "I1234567890abcdef",
  "subject": "Test change",
  "status": "NEW",
  "owner": {
    "_account_id": 1000,
    "name": "John Doe",
    "email": "john@example.com"
  }
}""",
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

            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            val change = client.getChange("project", "123")
            assertNotNull(change)
            assertEquals("project", change.project)
            assertEquals("I1234567890abcdef", change.changeId)
            assertEquals("Test change", change.subject)
            assertEquals("NEW", change.status)
            assertEquals("John Doe", change.owner.name)

            client.close()
        }

    @Test
    fun testPostReview() =
        runTest {
            val mockEngine = MockEngine { request ->
                assertEquals("https://gerrit.example.com/a/changes/123/revisions/current/review", request.url.toString())
                respond(
                    content = """)]}'
{
  "labels": {
    "Code-Review": 0
  }
}""",
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

            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "test-token",
                username = "user",
                httpClient = httpClient
            )

            val result = client.postReview("123", "Looks good to me!")
            assertNotNull(result)
            assertNotNull(result.labels)

            client.close()
        }

    @Test
    fun testListComments() =
        runTest {
            val mockEngine = MockEngine { request ->
                assertEquals("https://gerrit.example.com/changes/123?o=MESSAGES", request.url.toString())
                respond(
                    content = """)]}'
{
  "id": "project~master~I1234567890abcdef",
  "project": "project",
  "branch": "master",
  "change_id": "I1234567890abcdef",
  "subject": "Test change",
  "status": "NEW",
  "owner": {
    "_account_id": 1000,
    "name": "John Doe"
  },
  "messages": [
    {
      "id": "msg1",
      "message": "Uploaded patch set 1.",
      "author": {
        "_account_id": 1000,
        "name": "John Doe"
      }
    },
    {
      "id": "msg2",
      "message": "LGTM",
      "author": {
        "_account_id": 2000,
        "name": "Jane Smith"
      }
    }
  ]
}""",
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

            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            val messages = client.listComments("123")
            assertNotNull(messages)
            assertEquals(2, messages.size)
            assertEquals("Uploaded patch set 1.", messages[0].message)
            assertEquals("LGTM", messages[1].message)

            client.close()
        }

    @Test
    fun testSetReview() =
        runTest {
            val mockEngine = MockEngine { request ->
                assertEquals("https://gerrit.example.com/a/changes/123/revisions/current/review", request.url.toString())
                respond(
                    content = """)]}'
{
  "labels": {
    "Code-Review": 1,
    "Verified": 1
  }
}""",
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

            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "test-token",
                username = "user",
                httpClient = httpClient
            )

            val labels = mapOf(
                "Code-Review" to 1,
                "Verified" to 1
            )
            val result = client.setReview("123", labels)
            assertNotNull(result)
            assertNotNull(result.labels)
            assertEquals(1, result.labels?.get("Code-Review"))
            assertEquals(1, result.labels?.get("Verified"))

            client.close()
        }

    @Test
    fun testGerritJsonPrefixStripping() =
        runTest {
            // Test that the Gerrit JSON security prefix ")]}" is properly handled
            val mockEngine = MockEngine { request ->
                respond(
                    content = """)]}'
{
  "id": "test~master~I123",
  "project": "test",
  "branch": "master",
  "change_id": "I123",
  "subject": "Test",
  "status": "NEW",
  "owner": {"_account_id": 1, "name": "Test User"}
}""",
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

            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            val change = client.getChange("test", "123")
            assertNotNull(change)
            assertEquals("test", change.project)

            client.close()
        }

    @Test
    fun testProjectIdEncoding() =
        runTest {
            // Test that project IDs with slashes are properly URL-encoded
            val mockEngine = MockEngine { request ->
                assertTrue(request.url.toString().contains("my%2Fproject~456"))
                respond(
                    content = """)]}'
{
  "id": "my/project~master~I456",
  "project": "my/project",
  "branch": "master",
  "change_id": "I456",
  "subject": "Test",
  "status": "NEW",
  "owner": {"_account_id": 1, "name": "Test User"}
}""",
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

            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            val change = client.getChange("my/project", "456")
            assertNotNull(change)
            assertEquals("my/project", change.project)

            client.close()
        }

    @Test
    fun `fetchCurrentAccountId returns account ID from accounts-self`() =
        runTest {
            val mockEngine = MockEngine {
                respond(
                    content = ByteReadChannel(
                        """)]}'
                    {"_account_id": 1000042, "name": "Bot User", "username": "bot"}"""
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(KarandashJson.Default) }
            }
            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            assertEquals(1000042L, client.fetchCurrentAccountId())
            // Verify caching
            assertEquals(1000042L, client.fetchCurrentAccountId())

            client.close()
        }

    @Test
    fun `fetchCurrentAccountId returns null on API failure`() =
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
            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "bad-token",
                httpClient = httpClient
            )

            assertEquals(null, client.fetchCurrentAccountId())

            client.close()
        }

    @Test
    fun `getCurrentCodeReviewVote returns vote value for current user`() =
        runTest {
            var requestCount = 0
            val mockEngine = MockEngine {
                requestCount++
                if (requestCount == 1) {
                    respond(
                        content = ByteReadChannel(
                            """)]}'
                            {"_account_id": 42, "name": "Bot", "username": "bot"}"""
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        content = ByteReadChannel(
                            """)]}'
                            {
                                "id": "proj~main~I1234", "project": "proj", "change_id": "I1234",
                                "subject": "Test change", "status": "NEW", "branch": "main",
                                "owner": {"_account_id": 1, "name": "Author"},
                                "labels": {
                                    "Code-Review": {
                                        "all": [{"value": 2, "_account_id": 42, "name": "Bot"}]
                                    }
                                }
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
            val client = GerritClient(
                baseUrl = "https://gerrit.example.com",
                token = "test-token",
                httpClient = httpClient
            )

            assertEquals(2, client.getCurrentCodeReviewVote("proj", "I1234"))

            client.close()
        }
}
