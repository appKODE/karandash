package io.karandash.enrichment.jira

import io.karandash.api.PluginContext
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Jira remote links reach the linked issues so Confluence can follow them. */
class JiraRegressionTest {
    private val context: PluginContext =
        object : PluginContext {
            override val pluginConfig = buildJsonObject { put("url", "https://test.atlassian.net") }
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? =
                if (name == "JIRA_TOKEN") {
                    "t"
                } else if (name == "JIRA_EMAIL") {
                    "e@x.io"
                } else {
                    null
                }

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private val issue = """{"id":"1","key":"PROJ-5","fields":{"summary":"S","description":null,"issuetype":{"name":"Story"},""" +
        """"priority":{"name":"High"},"status":{"name":"Open"},"labels":[],"issuelinks":[]}}"""

    private val remoteLinks =
        """[{"id":10,"object":{"url":"https://test.atlassian.net/wiki/spaces/ENG/pages/12345/Spec","title":"Spec"}}]"""

    private fun mr(): MergeRequest = createTestMergeRequest(title = "PROJ-5: change")

    @Test
    fun `remote links are fetched and exposed as remote urls`() =
        runTest {
            val paths = mutableListOf<String>()
            val engine = MockEngine { request ->
                paths += request.url.encodedPath
                val body = if (request.url.encodedPath.endsWith("/remotelink")) remoteLinks else issue
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val provider = JiraEnrichmentProvider(testEngine = engine)
            provider.initialize(context)

            val result = provider.enrich(mr(), JsonObject(emptyMap()))

            assertEquals(true, paths.any { it == "/rest/api/3/issue/PROJ-5/remotelink" })
            val links = result
                .getValue(EnrichmentKeys.JIRA_LINKED_ISSUES)
                .jsonObject
                .getValue("PROJ-5")
                .jsonArray
            assertEquals(
                listOf("https://test.atlassian.net/wiki/spaces/ENG/pages/12345/Spec"),
                links.map {
                    it.jsonObject
                        .getValue("remoteUrl")
                        .jsonPrimitive.content
                },
            )
            provider.shutdown()
        }
}
