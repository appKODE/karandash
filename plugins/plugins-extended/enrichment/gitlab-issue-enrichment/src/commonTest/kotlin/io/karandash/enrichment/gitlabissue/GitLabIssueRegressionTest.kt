package io.karandash.enrichment.gitlabissue

import io.karandash.api.context.EnrichmentContext
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** project-id accepts a number or a string, a bad value warns instead of using project 0. */
class GitLabIssueRegressionTest {
    private suspend fun requestedPaths(config: String): List<String> {
        val paths = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                paths += request.url.encodedPath
                respond("""{"iid":6,"title":"T","state":"opened"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val provider = GitLabIssueEnrichmentProvider(engine)
        val context = TestPluginContext(KarandashJson.Default.parseToJsonElement(config), mapOf("GITLAB_TOKEN" to "t"))
        assertTrue(provider.initialize(context))
        provider.enrich(createTestMergeRequest(title = "#6"), JsonObject(emptyMap()), EnrichmentContext.EMPTY)
        provider.shutdown()
        return paths
    }

    @Test
    fun `project id given as a string is accepted`() =
        runTest {
            assertEquals(listOf("/api/v4/projects/123/issues/6"), requestedPaths("""{"project-id":"123"}"""))
        }

    @Test
    fun `project id given as a path is encoded`() =
        runTest {
            assertEquals(listOf("/api/v4/projects/group%2Frepo/issues/6"), requestedPaths("""{"project-id":"group/repo"}"""))
        }

    @Test
    fun `an unusable project id disables the provider with a warning`() =
        runTest {
            val context =
                TestPluginContext(KarandashJson.Default.parseToJsonElement("""{"project-id":{"x":1}}"""), mapOf("GITLAB_TOKEN" to "t"))
            assertFalse(GitLabIssueEnrichmentProvider().initialize(context))
            assertTrue(context.warnings.any { it.contains("invalid config") })
        }
}
