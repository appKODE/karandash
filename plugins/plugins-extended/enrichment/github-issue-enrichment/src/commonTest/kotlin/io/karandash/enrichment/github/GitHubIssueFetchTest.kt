package io.karandash.enrichment.github

import io.karandash.api.context.EnrichmentContext
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubIssueFetchTest {
    private val token = mapOf("GITHUB_TOKEN" to "t")

    private fun context(config: String) = TestPluginContext(KarandashJson.Default.parseToJsonElement(config), token)

    private suspend fun enrich(
        title: String,
        config: String = CONFIG,
        requests: MutableList<String> = mutableListOf(),
        route: (String) -> Pair<HttpStatusCode, String>,
    ): Map<String, Any> {
        val engine =
            MockEngine { request ->
                requests += request.url.encodedPath
                val (status, body) = route(request.url.encodedPath)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val provider = GitHubIssueEnrichmentProvider(engine)
        assertTrue(provider.initialize(context(config)))
        val result = provider.enrich(createTestMergeRequest(title = title), JsonObject(emptyMap()), EnrichmentContext.EMPTY)
        provider.shutdown()
        return result
    }

    @Test
    fun `cross repo reference to another repository is not fetched from the configured repo`() =
        runTest {
            val requests = mutableListOf<String>()

            enrich("see other/lib#5 and acme/backend#6", requests = requests) { HttpStatusCode.OK to issue(6) }

            assertEquals(listOf("/repos/acme/backend/issues/6"), requests)
        }

    @Test
    fun `issue details carry labels milestone and assignees`() =
        runTest {
            val body =
                """{"number":42,"title":"Login","body":null,"state":"closed","labels":[{"name":"bug"},{"name":"p1"}],""" +
                    """"milestone":{"title":"v2"},"assignees":[{"login":"alice"}],"extra":1}"""

            val result = enrich("Fixes #42") { HttpStatusCode.OK to body }

            val issues = result[EnrichmentKeys.GITHUB_ISSUES] as JsonObject
            assertEquals(
                GitHubIssueDetails(42, "Login", null, "closed", listOf("bug", "p1"), "v2", listOf("alice")),
                KarandashJson.Default.decodeFromJsonElement(GitHubIssueDetails.serializer(), checkNotNull(issues["42"])),
            )
        }

    @Test
    fun `non success statuses and unreadable bodies are skipped with warnings`() =
        runTest {
            val ctx = context(CONFIG)
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath.substringAfterLast('/')) {
                        "1" -> respond("", HttpStatusCode.Unauthorized)
                        "2" -> respond("", HttpStatusCode.Forbidden)
                        "3" -> respond("<html>", HttpStatusCode.OK)
                        else -> respond(issue(4), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            val provider = GitHubIssueEnrichmentProvider(engine)
            provider.initialize(ctx)

            val result = provider.enrich(createTestMergeRequest(title = "#1 #2 #3 #4"), JsonObject(emptyMap()), EnrichmentContext.EMPTY)

            assertEquals(setOf("4"), (result[EnrichmentKeys.GITHUB_ISSUES] as JsonObject).keys)
            assertEquals(
                listOf(
                    "GitHub: failed to fetch issue #1: Authentication failed for GitHubIssue API: HTTP 401",
                    "GitHub: failed to fetch issue #2: Access denied for GitHubIssue API: HTTP 403",
                ),
                ctx.warnings.take(2).map { it.substringBefore(" Unauthorized").substringBefore(" Forbidden") },
            )
            assertTrue(ctx.warnings[2].startsWith("GitHub: failed to fetch issue #3:"))
        }

    @Test
    fun `max issues per mr caps fetches and all misses give no enrichment`() =
        runTest {
            val requests = mutableListOf<String>()
            val config = CONFIG.replace("}", ""","issues":{"max-issues-per-mr":2}}""")

            val result = enrich("#1 #2 #3", config, requests) { HttpStatusCode.NotFound to "" }

            assertEquals(2, requests.size)
            assertTrue(result.isEmpty())
            assertTrue(enrich("no refs") { HttpStatusCode.OK to issue(1) }.isEmpty())
        }

    @Test
    fun `disabled block or a non object config disable the provider while a missing repo skips it`() =
        runTest {
            val disabled = context("""{"owner":"acme","repo":"backend","issues":{"enabled":false}}""")
            val noRepo = context("""{"owner":"acme"}""")
            val array = TestPluginContext(JsonArray(emptyList()), token)

            assertFalse(GitHubIssueEnrichmentProvider().initialize(disabled))
            assertTrue(GitHubIssueEnrichmentProvider().initialize(noRepo))
            assertEquals(
                listOf("GitHub issue enrichment: no owner/repo and no GITHUB_REPOSITORY: issue references not fetched"),
                noRepo.warnings,
            )
            assertFalse(GitHubIssueEnrichmentProvider().initialize(array))
            assertTrue(array.warnings.first().startsWith("enrichment.github-issue: invalid config, using defaults:"))
            val uninitialized = GitHubIssueEnrichmentProvider()
            assertTrue(
                uninitialized.enrich(createTestMergeRequest(title = "#1"), JsonObject(emptyMap()), EnrichmentContext.EMPTY).isEmpty()
            )
        }

    @Test
    fun `github actions env supplies the repository and api url`() =
        runTest {
            val urls = mutableListOf<String>()
            val engine = MockEngine { request ->
                urls += request.url.host + request.url.encodedPath
                respond(issue(6), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val env = mapOf("GITHUB_TOKEN" to "t", "GITHUB_REPOSITORY" to "acme/app", "GITHUB_API_URL" to "https://ghe.acme.com/api/v3")
            val provider = GitHubIssueEnrichmentProvider(engine)
            assertTrue(provider.initialize(TestPluginContext(JsonObject(emptyMap()), env)))
            val result = provider.enrich(createTestMergeRequest(title = "fixes #6"), JsonObject(emptyMap()), EnrichmentContext.EMPTY)
            provider.shutdown()

            assertTrue(EnrichmentKeys.GITHUB_ISSUES in result)
            assertEquals(listOf("ghe.acme.com/api/v3/repos/acme/app/issues/6"), urls)
        }

    private fun issue(number: Int) = """{"number":$number,"title":"T","state":"open"}"""

    private companion object {
        const val CONFIG = """{"owner":"acme","repo":"backend"}"""
    }
}
