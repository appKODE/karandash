package io.karandash.enrichment.jira

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JiraEnrichmentProviderTest {
    private fun testContext() = TestPluginContext()

    private fun testContextWithAuth(
        url: String = "https://test.atlassian.net",
        allowLocalhost: Boolean = false,
    ) = TestPluginContext(
        buildJsonObject { put("url", url) },
        mapOf("JIRA_TOKEN" to "test-token", "JIRA_EMAIL" to "user@example.com"),
        allowLocalhost,
    )

    private fun makeMR(
        title: String = "Default title",
        description: String? = null,
        sourceBranch: String = "feature/test",
        commits: List<Commit> = emptyList(),
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = title,
            description = description,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = sourceBranch,
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = emptyList(),
            commits = commits,
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()

        override fun debug(message: String) {}

        override fun info(message: String) {}

        override fun warn(message: String) {
            messages.add(message)
        }

        override fun error(message: String) {}
    }

    private fun testContextWithConfig(
        config: JsonObject,
        logger: Logger,
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig = config
            override val logger: Logger = logger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun issueJson(
        key: String,
        summary: String = "Test issue"
    ) = """
        {
          "id": "10001",
          "key": "$key",
          "fields": {
            "summary": "$summary",
            "description": null,
            "issuetype": { "name": "Story" },
            "priority": { "name": "High" },
            "status": { "name": "Open", "statusCategory": { "name": "To Do" } },
            "labels": [],
            "assignee": null,
            "customfield_10014": null,
            "issuelinks": []
          }
        }
        """.trimIndent()

    // ── Metadata & lifecycle ─────────────────────────────────────────────────

    @Test
    fun moduleMetadata() {
        val provider = JiraEnrichmentProvider()
        assertEquals(JiraEnrichmentProvider.ID, provider.id)
        assertEquals(ModuleType.ENRICHMENT_PROVIDER, provider.type)
        val ids = provider.providedEnrichment
        assertEquals(4, ids.size)
        assertTrue(ids.contains(EnrichmentKeys.JIRA_TICKET_KEYS))
        assertTrue(ids.contains(EnrichmentKeys.JIRA_TICKETS))
        assertTrue(ids.contains(EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA))
        assertTrue(ids.contains(EnrichmentKeys.JIRA_LINKED_ISSUES))
    }

    @Test
    fun initializeReturnsTrue() =
        runTest {
            val provider = JiraEnrichmentProvider()
            assertTrue(provider.initialize(testContext()))
        }

    @Test
    fun warnsOnUnknownSubBlockKeyTypo() =
        runTest {
            val logger = CapturingLogger()
            val config = buildJsonObject { put("ticketss", buildJsonObject { put("enabled", true) }) }
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContextWithConfig(config, logger))

            assertTrue(logger.messages.any { it.contains("enrichment.jira.ticketss") && it.contains("tickets") })
        }

    @Test
    fun noWarningsOnWellFormedConfig() =
        runTest {
            val logger = CapturingLogger()
            val config = buildJsonObject {
                put("url", "https://acme.atlassian.net")
                put("token", "secret")
                put("tickets", buildJsonObject { put("enabled", true) })
            }
            JiraEnrichmentProvider().initialize(testContextWithConfig(config, logger))

            assertTrue(logger.messages.isEmpty(), "Expected no warnings, got: ${logger.messages}")
        }

    // the `enrich: [...]` list is a live form, no warning
    @Test
    fun acceptsEnrichListWithoutWarning() =
        runTest {
            val logger = CapturingLogger()
            val config = buildJsonObject {
                put("url", "https://acme.atlassian.net")
                put("enrich", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("jira:tickets")) })
            }
            JiraEnrichmentProvider().initialize(testContextWithConfig(config, logger))

            assertTrue(
                logger.messages.none { it.contains("enrich: [...]") || it.contains("deprecated") },
                "Expected no enrich-list warning, got: ${logger.messages}",
            )
        }

    @Test
    fun shutdownWithoutInitializeDoesNotThrow() =
        runTest {
            JiraEnrichmentProvider().shutdown()
        }

    @Test
    fun shutdownIsIdempotent() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())
            provider.shutdown()
            provider.shutdown()
        }

    // ── Ticket-key extraction (no API required) ──────────────────────────────

    @Test
    fun extractsTicketKeysFromTitle() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(title = "PROJ-123: Fix login bug")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(JiraEnrichmentProvider.ENRICHMENT_TICKET_KEYS))
            val keys = result.getValue(JiraEnrichmentProvider.ENRICHMENT_TICKET_KEYS).jsonArray
            assertEquals(1, keys.size)
            assertEquals("PROJ-123", keys[0].jsonPrimitive.content)
        }

    @Test
    fun extractsTicketKeysFromDescription() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(
                title = "Fix bug",
                description = "Resolves TEAM-456 and TEAM-789",
            )
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            val keys = result.getValue(JiraEnrichmentProvider.ENRICHMENT_TICKET_KEYS).jsonArray
            assertEquals(2, keys.size)
            assertTrue(keys.any { it.jsonPrimitive.content == "TEAM-456" })
            assertTrue(keys.any { it.jsonPrimitive.content == "TEAM-789" })
        }

    @Test
    fun extractsTicketKeysFromBranchName() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(
                title = "Fix something",
                sourceBranch = "feature/ABC-42-login-fix",
            )
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            val keys = result.getValue(JiraEnrichmentProvider.ENRICHMENT_TICKET_KEYS).jsonArray
            assertEquals(1, keys.size)
            assertEquals("ABC-42", keys[0].jsonPrimitive.content)
        }

    @Test
    fun extractsTicketKeysFromCommitMessages() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val commits = listOf(
                Commit(sha = "abc", message = "DEV-10 first", author = CommitAuthor("a", "a@x.com"), timestamp = ""),
                Commit(sha = "def", message = "DEV-20 second", author = CommitAuthor("a", "a@x.com"), timestamp = ""),
            )
            val mr = makeMR(title = "Batch fix", commits = commits)
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            val keys = result.getValue(JiraEnrichmentProvider.ENRICHMENT_TICKET_KEYS).jsonArray
            assertEquals(2, keys.size)
        }

    @Test
    fun deduplicatesTicketKeys() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(
                title = "PROJ-123: Fix bug",
                description = "Also related to PROJ-123",
            )
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            val keys = result.getValue(JiraEnrichmentProvider.ENRICHMENT_TICKET_KEYS).jsonArray
            assertEquals(1, keys.size)
        }

    @Test
    fun returnsEmptyMapWhenNoTicketsFound() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(title = "Fix minor typo")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.isEmpty())
        }

    @Test
    fun enrichWithEmptyInputDoesNotThrow() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(title = "", description = null, sourceBranch = "")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.isEmpty())
        }

    @Test
    fun doesNotMatchSha256FalsePositive() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(
                title = "Fix SHA-256 hash collision",
                description = "Use SHA-256 instead of MD5",
            )
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.isEmpty(), "SHA-256 must not be matched as a JIRA key")
        }

    @Test
    fun doesNotMatchKtorVersionFalsePositive() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(
                title = "Bump KTOR-9141 workaround",
                description = "See issue KTOR-9141 for details",
            )
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.isEmpty(), "KTOR-9141 must not be matched as a JIRA key")
        }

    @Test
    fun doesNotMatchVersionStrings() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(
                title = "Update UTF-8 encoding",
                description = "ISO-8859 compatibility note",
            )
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.isEmpty(), "UTF-8 and ISO-8859 must not be matched as JIRA keys")
        }

    @Test
    fun stillMatchesValidJiraKeysAlongsideFalsePositiveCandidates() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext())

            val mr = makeMR(
                title = "PROJ-42: Use SHA-256 for KTOR-9141 fix",
                description = "Resolves TEAM-7 while keeping UTF-8 support",
            )
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            val keys = result
                .getValue(JiraEnrichmentProvider.ENRICHMENT_TICKET_KEYS)
                .jsonArray
                .map { it.jsonPrimitive.content }
            assertEquals(2, keys.size, "Expected PROJ-42 and TEAM-7, got: $keys")
            assertTrue("PROJ-42" in keys)
            assertTrue("TEAM-7" in keys)
        }

    // ── No API client - only ticket-keys emitted ─────────────────────────────

    @Test
    fun noApiClientEmitsOnlyTicketKeys() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContext()) // no URL/token → no API client

            val mr = makeMR(title = "PROJ-123: fix bug")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(EnrichmentKeys.JIRA_TICKET_KEYS))
            assertFalse(result.containsKey(EnrichmentKeys.JIRA_TICKETS))
            assertFalse(result.containsKey(EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA))
        }

    // ── With API client - tickets and AC emitted ──────────────────────────────

    @Test
    fun apiClientFetchesTicketsAndEmitsJiraTickets() =
        runTest {
            val engine = MockEngine { respond(issueJson("PROJ-123", "Login bug"), HttpStatusCode.OK, jsonHeaders()) }
            val provider = JiraEnrichmentProvider(testEngine = engine)
            provider.initialize(testContextWithAuth())

            val mr = makeMR(title = "PROJ-123: fix login")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(EnrichmentKeys.JIRA_TICKETS))
            val tickets = result.getValue(EnrichmentKeys.JIRA_TICKETS).jsonObject
            assertTrue(tickets.containsKey("PROJ-123"))
            assertEquals(
                "Login bug",
                tickets["PROJ-123"]
                    ?.jsonObject
                    ?.get("summary")
                    ?.jsonPrimitive
                    ?.content
            )
            provider.shutdown()
        }

    @Test
    fun localhostJiraIsReachedOnlyWithAllowLocalhost() =
        runTest {
            val engine = MockEngine { respond(issueJson("PROJ-7", "Local"), HttpStatusCode.OK, jsonHeaders()) }
            val mr = makeMR(title = "PROJ-7: local mock")

            val blocked = JiraEnrichmentProvider(testEngine = engine)
            blocked.initialize(testContextWithAuth(url = "http://localhost:8080"))
            assertFalse(blocked.enrich(mr, JsonObject(emptyMap())).containsKey(EnrichmentKeys.JIRA_TICKETS))
            blocked.shutdown()

            val allowed = JiraEnrichmentProvider(testEngine = engine)
            allowed.initialize(testContextWithAuth(url = "http://localhost:8080", allowLocalhost = true))
            assertTrue(allowed.enrich(mr, JsonObject(emptyMap())).containsKey(EnrichmentKeys.JIRA_TICKETS))
            allowed.shutdown()
        }

    @Test
    fun apiClientEmitsAcceptanceCriteria() =
        runTest {
            val engine = MockEngine { respond(issueJson("PROJ-99"), HttpStatusCode.OK, jsonHeaders()) }
            val provider = JiraEnrichmentProvider(testEngine = engine)
            provider.initialize(testContextWithAuth())

            val mr = makeMR(title = "PROJ-99: something")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA))
            provider.shutdown()
        }

    @Test
    fun apiClientNotFoundReturnsOnlyTicketKeys() =
        runTest {
            val engine = MockEngine {
                respond("""{"errorMessages":["Not found"]}""", HttpStatusCode.NotFound, jsonHeaders())
            }
            val provider = JiraEnrichmentProvider(testEngine = engine)
            provider.initialize(testContextWithAuth())

            val mr = makeMR(title = "PROJ-123: fix")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(EnrichmentKeys.JIRA_TICKET_KEYS))
            assertFalse(result.containsKey(EnrichmentKeys.JIRA_TICKETS), "404 should not produce jira:tickets")
            provider.shutdown()
        }

    @Test
    fun reinitializeWithoutUrlDropsThePreviousApiClient() =
        runTest {
            val engine = MockEngine { respond(issueJson("PROJ-123", "Login bug"), HttpStatusCode.OK, jsonHeaders()) }
            val provider = JiraEnrichmentProvider(testEngine = engine)
            val mr = makeMR(title = "PROJ-123: fix login")
            provider.initialize(testContextWithAuth())
            assertTrue(provider.enrich(mr, JsonObject(emptyMap())).containsKey(EnrichmentKeys.JIRA_TICKETS))

            provider.initialize(testContext())

            val result = provider.enrich(mr, JsonObject(emptyMap()))
            assertFalse(result.containsKey(EnrichmentKeys.JIRA_TICKETS), "stale client from the previous config was used")
            // issue fetch plus its remote links fetch, then nothing after the client was dropped
            assertEquals(2, engine.requestHistory.size)
            provider.shutdown()
        }

    // ── Linked issues ────────────────────────────────────────────────────────

    @Test
    fun apiClientEmitsLinkedIssues() =
        runTest {
            val issueWithLinks = """
                {
                  "id": "10001",
                  "key": "PROJ-123",
                  "fields": {
                    "summary": "Main issue",
                    "description": null,
                    "issuetype": { "name": "Story" },
                    "priority": { "name": "High" },
                    "status": { "name": "Open", "statusCategory": { "name": "To Do" } },
                    "labels": [],
                    "assignee": null,
                    "customfield_10014": null,
                    "issuelinks": [
                      {
                        "type": { "name": "Blocks", "inward": "is blocked by", "outward": "blocks" },
                        "outwardIssue": {
                          "key": "PROJ-456",
                          "fields": { "summary": "Blocked issue", "status": { "name": "In Progress", "statusCategory": { "name": "In Progress" } } }
                        }
                      }
                    ]
                  }
                }
            """.trimIndent()
            val engine = MockEngine { respond(issueWithLinks, HttpStatusCode.OK, jsonHeaders()) }
            val provider = JiraEnrichmentProvider(testEngine = engine)
            provider.initialize(testContextWithAuth())

            val mr = makeMR(title = "PROJ-123: fix")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(EnrichmentKeys.JIRA_LINKED_ISSUES))
            val linked = result.getValue(EnrichmentKeys.JIRA_LINKED_ISSUES).jsonObject
            assertTrue(linked.containsKey("PROJ-123"))
            val links = linked["PROJ-123"]!!.jsonArray
            assertEquals(1, links.size)
            assertEquals("PROJ-456", links[0].jsonObject["targetKey"]?.jsonPrimitive?.content)
            assertEquals("blocks", links[0].jsonObject["relation"]?.jsonPrimitive?.content)
            provider.shutdown()
        }

    @Test
    fun linkedIssuesEmptyWhenNoLinks() =
        runTest {
            val engine = MockEngine { respond(issueJson("PROJ-123"), HttpStatusCode.OK, jsonHeaders()) }
            val provider = JiraEnrichmentProvider(testEngine = engine)
            provider.initialize(testContextWithAuth())

            val mr = makeMR(title = "PROJ-123: fix")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(EnrichmentKeys.JIRA_LINKED_ISSUES))
            val linked = result.getValue(EnrichmentKeys.JIRA_LINKED_ISSUES).jsonObject
            val links = linked["PROJ-123"]!!.jsonArray
            assertTrue(links.isEmpty())
            provider.shutdown()
        }

    @Test
    fun `a url without a token gives a skip reason`() =
        runTest {
            val provider = JiraEnrichmentProvider()
            provider.initialize(testContextWithConfig(buildJsonObject { put("url", "https://acme.atlassian.net") }, NoOpLogger))

            assertTrue(provider.skipReason().orEmpty().contains("JIRA_TOKEN"), provider.skipReason())
        }

    @Test
    fun `a url with a token or no url at all gives no skip reason`() =
        runTest {
            val withToken = JiraEnrichmentProvider()
            withToken.initialize(testContextWithAuth())
            val noUrl = JiraEnrichmentProvider()
            noUrl.initialize(testContext())

            assertNull(withToken.skipReason())
            assertNull(noUrl.skipReason())
        }
}
