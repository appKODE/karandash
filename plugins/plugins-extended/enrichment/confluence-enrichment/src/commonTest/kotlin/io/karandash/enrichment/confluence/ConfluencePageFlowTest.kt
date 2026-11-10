package io.karandash.enrichment.confluence

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
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfluencePageFlowTest {
    private suspend fun pages(
        description: String,
        config: String = CONFIG,
        upstream: EnrichmentContext = EnrichmentContext.EMPTY,
        requests: MutableList<String> = mutableListOf(),
    ): Map<String, ConfluencePageContent> {
        val engine =
            MockEngine { request ->
                requests += request.url.encodedPath
                val id = request.url.encodedPath.substringAfterLast('/')
                respond(page(id), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val provider = ConfluenceEnrichmentProvider(testEngine = engine)
        assertTrue(provider.initialize(TestPluginContext(KarandashJson.Default.parseToJsonElement(config))))
        val result = provider.enrich(createTestMergeRequest(description = description), JsonObject(emptyMap()), upstream)
        provider.shutdown()
        val json = result[EnrichmentKeys.CONFLUENCE_PAGES] as? JsonObject ?: return emptyMap()
        return json.mapValues { (_, v) -> KarandashJson.Default.decodeFromJsonElement(ConfluencePageContent.serializer(), v) }
    }

    @Test
    fun `page bodies are truncated and source url falls back to the api url without web links`() =
        runTest {
            val result = pages("$BASE/spaces/ENG/pages/1", CONFIG.replace("}", ""","pages":{"max-chars-per-page":4}}"""))

            assertEquals(
                ConfluencePageContent("1", "Page 1", 2, "Body", "$BASE/rest/api/content/1?expand=body.storage,version"),
                result["1"],
            )
        }

    @Test
    fun `max pages per mr caps the fetches`() =
        runTest {
            val requests = mutableListOf<String>()
            val config = CONFIG.replace("}", ""","pages":{"max-pages-per-mr":2}}""")

            val result = pages("$BASE/spaces/A/pages/1 $BASE/spaces/A/pages/2 $BASE/spaces/A/pages/3", config, requests = requests)

            assertEquals(setOf("1", "2"), result.keys)
            assertEquals(2, requests.size)
        }

    @Test
    fun `page links on linked jira issues are fetched unless jira enrichment is missing`() =
        runTest {
            val linked =
                KarandashJson.Default.parseToJsonElement(
                    """{"PROJ-1":[{"remoteUrl":"$BASE/spaces/ENG/pages/9"},{"remoteUrl":""},{"title":"x"},"bad"],"PROJ-2":{}}""",
                )
            val upstream = EnrichmentContext(mapOf(EnrichmentKeys.JIRA_LINKED_ISSUES to linked))
            val missing = EnrichmentContext(mapOf(EnrichmentKeys.JIRA_LINKED_ISSUES to linked), setOf(EnrichmentKeys.JIRA_LINKED_ISSUES))
            val notAnObject = EnrichmentContext(mapOf(EnrichmentKeys.JIRA_LINKED_ISSUES to KarandashJson.Default.parseToJsonElement("[]")))

            assertEquals(setOf("9"), pages("no links", upstream = upstream).keys)
            assertTrue(pages("no links", upstream = missing).isEmpty())
            assertTrue(pages("no links", upstream = notAnObject).isEmpty())
        }

    @Test
    fun `token and email fall back to environment variables`() =
        runTest {
            val noToken = TestPluginContext(KarandashJson.Default.parseToJsonElement("""{"url":"$BASE"}"""))
            val jiraToken = TestPluginContext(KarandashJson.Default.parseToJsonElement("""{"url":"$BASE"}"""), mapOf("JIRA_TOKEN" to "t"))

            val skipped = ConfluenceEnrichmentProvider()
            assertEquals(true, skipped.initialize(noToken))
            assertEquals(true, skipped.skipReason() != null)
            val withJiraToken = ConfluenceEnrichmentProvider()
            assertEquals(true, withJiraToken.initialize(jiraToken))
            assertEquals(null, withJiraToken.skipReason())
        }

    private fun page(id: String) =
        """{"id":"$id","title":"Page $id","version":{"number":2},"body":{"storage":{"value":"<p>Body text</p>"}}}"""

    private companion object {
        const val BASE = "https://test.atlassian.net/wiki"
        const val CONFIG = """{"url":"$BASE","token":"t"}"""
    }
}
