package io.karandash.enrichment.jira

import io.karandash.providers.common.EnrichmentHttp
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JiraApiClientTest {
    private val issueJson = """
        {
          "id": "10001",
          "key": "ABC-123",
          "fields": {
            "summary": "Fix login bug",
            "description": null,
            "issuetype": { "name": "Story" },
            "priority": { "name": "High" },
            "status": { "name": "In Progress", "statusCategory": { "name": "In Progress" } },
            "labels": ["backend", "auth"],
            "assignee": { "displayName": "Alice" },
            "customfield_10014": null,
            "issuelinks": []
          }
        }
    """.trimIndent()

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `fetchIssue returns parsed response for 200`() =
        runTest {
            val engine = MockEngine { respond(issueJson, HttpStatusCode.OK, jsonHeaders()) }
            val client = jiraClient("https://acme.atlassian.net", "user@example.com", "token", engine = engine)
            val result = client.fetchIssue("ABC-123")
            assertEquals("ABC-123", result?.key)
            assertEquals("Fix login bug", result?.fields?.summary)
            assertEquals("High", result?.fields?.priority?.name)
            assertEquals(listOf("backend", "auth"), result?.fields?.labels)
            client.close()
        }

    @Test
    fun `a token without an email is sent as a Bearer personal access token`() =
        runTest {
            val auth = mutableListOf<String?>()
            val engine = MockEngine { request ->
                auth += request.headers[HttpHeaders.Authorization]
                respond(issueJson, HttpStatusCode.OK, jsonHeaders())
            }
            jiraClient("https://jira.acme.com", null, "pat-1", engine).fetchIssue("ABC-123")
            jiraClient("https://jira.acme.com", "user@example.com", "token", engine).fetchIssue("ABC-123")

            assertEquals("Bearer pat-1", auth[0])
            assertTrue(auth[1].orEmpty().startsWith("Basic "), auth[1])
        }

    @Test
    fun `fetchIssue returns null for 404`() =
        runTest {
            val engine = MockEngine {
                respond("""{"errorMessages":["Issue does not exist"]}""", HttpStatusCode.NotFound, jsonHeaders())
            }
            val client = jiraClient("https://acme.atlassian.net", "user@example.com", "token", engine = engine)
            assertNull(client.fetchIssue("MISSING-999"))
            client.close()
        }

    @Test
    fun `fetchIssue returns null for 401 auth failure`() =
        runTest {
            val engine = MockEngine {
                respond("""{"message":"401 Unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders())
            }
            val client = jiraClient("https://acme.atlassian.net", "user@example.com", "wrong-token", engine = engine)
            assertNull(client.fetchIssue("ABC-123"))
            client.close()
        }

    @Test
    fun `fetchIssue returns null for malformed JSON`() =
        runTest {
            val engine = MockEngine { respond("not-json", HttpStatusCode.OK, jsonHeaders()) }
            val client = jiraClient("https://acme.atlassian.net", "user@example.com", "token", engine = engine)
            assertNull(client.fetchIssue("ABC-123"))
            client.close()
        }

    @Test
    fun `fetchIssue parses issuelinks`() =
        runTest {
            val json = """
                {
                  "id": "10002", "key": "PROJ-1",
                  "fields": {
                    "summary": "Parent",
                    "issuelinks": [
                      {
                        "type": { "name": "blocks", "inward": "is blocked by", "outward": "blocks" },
                        "outwardIssue": { "key": "PROJ-2", "fields": { "summary": "Child", "status": { "name": "Open" } } }
                      }
                    ]
                  }
                }
            """.trimIndent()
            val engine = MockEngine { respond(json, HttpStatusCode.OK, jsonHeaders()) }
            val client = jiraClient("https://acme.atlassian.net", "user@example.com", "token", engine = engine)
            val result = client.fetchIssue("PROJ-1")
            assertEquals(1, result?.fields?.issueLinks?.size)
            assertEquals(
                "PROJ-2",
                result
                    ?.fields
                    ?.issueLinks
                    ?.first()
                    ?.outwardIssue
                    ?.key
            )
            client.close()
        }
}

/** A [JiraApiClient] with Basic email and token auth, built the way the provider builds it. */
internal fun jiraClient(
    baseUrl: String,
    email: String?,
    token: String,
    engine: HttpClientEngine,
) = JiraApiClient(
    EnrichmentHttp.client(TestPluginContext(), "Jira", baseUrl, EnrichmentHttp.atlassianAuth(email, token), engine),
    baseUrl,
)
