package io.karandash.enrichment.gitlabissue

import io.karandash.api.context.EnrichmentContext
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitLabIssueFetchTest {
    private val token = mapOf("GITLAB_TOKEN" to "t")

    private fun context(config: String) = TestPluginContext(KarandashJson.Default.parseToJsonElement(config), token)

    private suspend fun enrich(
        title: String,
        config: String = CONFIG,
        ctx: TestPluginContext = context(config),
        requests: MutableList<String> = mutableListOf(),
        route: (String) -> Pair<HttpStatusCode, String>,
    ): Map<String, JsonElement> {
        val engine =
            MockEngine { request ->
                requests += request.url.encodedPath
                val (status, body) = route(request.url.encodedPath)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val provider = GitLabIssueEnrichmentProvider(engine)
        assertTrue(provider.initialize(ctx))
        val result = provider.enrich(createTestMergeRequest(title = title), JsonObject(emptyMap()), EnrichmentContext.EMPTY)
        provider.shutdown()
        return result
    }

    @Test
    fun `cross project reference is not fetched from the configured project`() =
        runTest {
            val requests = mutableListOf<String>()

            enrich("see other/lib#5 and #6", requests = requests) { HttpStatusCode.OK to issue(6) }

            assertEquals(listOf("/api/v4/projects/123/issues/6"), requests)
        }

    @Test
    fun `issue details carry labels milestone and assignees`() =
        runTest {
            val body =
                """{"iid":42,"title":"Login","description":null,"state":"closed","labels":["bug","p1"],""" +
                    """"milestone":{"title":"v2"},"assignees":[{"username":"alice"}],"web_url":"https://g/42","extra":1}"""

            val result = enrich("Closes #42") { HttpStatusCode.OK to body }

            val issues = result[EnrichmentKeys.GITLAB_ISSUES] as JsonObject
            assertEquals(
                GitLabIssueDetails(42, "Login", null, "closed", listOf("bug", "p1"), "v2", listOf("alice")),
                KarandashJson.Default.decodeFromJsonElement(GitLabIssueDetails.serializer(), checkNotNull(issues["42"])),
            )
        }

    @Test
    fun `non success statuses and unreadable bodies are skipped with warnings`() =
        runTest {
            val ctx = context(CONFIG)
            val route: (String) -> Pair<HttpStatusCode, String> = { path ->
                when (path.substringAfterLast('/')) {
                    "1" -> HttpStatusCode.Unauthorized to ""
                    "2" -> HttpStatusCode.Forbidden to ""
                    "3" -> HttpStatusCode.OK to "<html>"
                    else -> HttpStatusCode.OK to issue(4)
                }
            }

            val result = enrich("#1 #2 #3 #4", ctx = ctx, route = route)

            assertEquals(setOf("4"), (result[EnrichmentKeys.GITLAB_ISSUES] as JsonObject).keys)
            assertEquals(
                listOf(
                    "GitLab: failed to fetch issue #1: Authentication failed for GitLabIssue API: HTTP 401",
                    "GitLab: failed to fetch issue #2: Access denied for GitLabIssue API: HTTP 403",
                ),
                ctx.warnings.take(2).map { it.substringBefore(" Unauthorized").substringBefore(" Forbidden") },
            )
            assertTrue(ctx.warnings[2].startsWith("GitLab: failed to fetch issue #3:"))
        }

    @Test
    fun `max issues per mr caps fetches and all misses give no enrichment`() =
        runTest {
            val requests = mutableListOf<String>()
            val config = CONFIG.replace("}", ""","issues":{"max-issues-per-mr":2}}""")

            val result = enrich("#1 #2 #3", config, requests = requests) { HttpStatusCode.NotFound to "" }

            assertEquals(2, requests.size)
            assertTrue(result.isEmpty())
            assertTrue(enrich("no refs") { HttpStatusCode.OK to issue(1) }.isEmpty())
        }

    @Test
    fun `disabled block or a non object config disable the provider`() =
        runTest {
            val disabled = context("""{"project-id":123,"issues":{"enabled":false}}""")
            val array = TestPluginContext(JsonArray(emptyList()), token)

            assertFalse(GitLabIssueEnrichmentProvider().initialize(disabled))
            assertFalse(GitLabIssueEnrichmentProvider().initialize(array))
            assertTrue(array.warnings.first().startsWith("enrichment.gitlab-issue: invalid config, using defaults:"))
            val uninitialized = GitLabIssueEnrichmentProvider()
            assertTrue(
                uninitialized.enrich(createTestMergeRequest(title = "#1"), JsonObject(emptyMap()), EnrichmentContext.EMPTY).isEmpty()
            )
        }

    @Test
    fun `gitlab ci env supplies server url project and job token`() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val engine = MockEngine { request ->
                requests += request
                respond(issue(6), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val env = mapOf("CI_SERVER_URL" to "https://gitlab.acme.com", "CI_PROJECT_ID" to "77", "CI_JOB_TOKEN" to "job-1")
            val provider = GitLabIssueEnrichmentProvider(engine)
            assertTrue(provider.initialize(TestPluginContext(JsonObject(emptyMap()), env)))
            val result = provider.enrich(createTestMergeRequest(title = "fixes #6"), JsonObject(emptyMap()), EnrichmentContext.EMPTY)
            provider.shutdown()

            assertTrue(EnrichmentKeys.GITLAB_ISSUES in result)
            assertEquals(listOf("gitlab.acme.com/api/v4/projects/77/issues/6"), requests.map { it.url.host + it.url.encodedPath })
            assertEquals("job-1", requests.single().headers["JOB-TOKEN"])
            assertEquals(null, requests.single().headers["PRIVATE-TOKEN"])
        }

    private fun issue(iid: Int) = """{"iid":$iid,"title":"T","state":"opened"}"""

    private companion object {
        const val CONFIG = """{"project-id":123}"""
    }
}
