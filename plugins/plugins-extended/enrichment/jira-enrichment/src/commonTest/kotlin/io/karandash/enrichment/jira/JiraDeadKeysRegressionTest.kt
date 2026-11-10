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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** max-requests-per-minute is enforced and acceptance-criteria.field is read. */
@OptIn(ExperimentalCoroutinesApi::class)
class JiraDeadKeysRegressionTest {
    private fun context(config: JsonObject): PluginContext =
        object : PluginContext {
            override val pluginConfig = config
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? =
                when (name) {
                    "JIRA_TOKEN" -> "test-token"
                    "JIRA_EMAIL" -> "user@example.com"
                    else -> null
                }

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private fun mr(title: String): MergeRequest = createTestMergeRequest(title = title)

    private fun issue(
        key: String,
        extraField: String = "",
    ) = """{"id":"1","key":"$key","fields":{"summary":"s","description":null$extraField}}"""

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `max requests per minute spaces out the fetches`() =
        runTest {
            val engine = MockEngine { request ->
                respond(issue(request.url.encodedPath.substringAfterLast('/')), HttpStatusCode.OK, json)
            }
            val provider = JiraEnrichmentProvider(testEngine = engine, timeSource = testTimeSource)
            val config = buildJsonObject {
                put("url", "https://test.atlassian.net")
                put("max-requests-per-minute", 2)
                put("linked-issues", buildJsonObject { put("enabled", false) })
            }
            provider.initialize(context(config))

            provider.enrich(mr("PROJ-1 PROJ-2 PROJ-3"), JsonObject(emptyMap()))

            assertEquals(3, engine.requestHistory.size)
            // 2 requests per minute: the third request waits until the first leaves the 60s window
            assertTrue(testScheduler.currentTime >= 60_000, "virtual time was ${testScheduler.currentTime}")
            provider.shutdown()
        }

    @Test
    fun `remote link fetches count against max requests per minute`() =
        runTest {
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/remotelink")) {
                    respond("[]", HttpStatusCode.OK, json)
                } else {
                    respond(issue(request.url.encodedPath.substringAfterLast('/')), HttpStatusCode.OK, json)
                }
            }
            val provider = JiraEnrichmentProvider(testEngine = engine, timeSource = testTimeSource)
            val config = buildJsonObject {
                put("url", "https://test.atlassian.net")
                put("max-requests-per-minute", 2)
            }
            provider.initialize(context(config))

            provider.enrich(mr("PROJ-1 PROJ-2"), JsonObject(emptyMap()))

            // two issue fetches fill the window, so the remote link fetches wait for it
            assertEquals(4, engine.requestHistory.size)
            assertTrue(testScheduler.currentTime >= 60_000, "virtual time was ${testScheduler.currentTime}")
            provider.shutdown()
        }

    private suspend fun TestScope.enrichKeys(count: Int): Long {
        val engine = MockEngine { request ->
            respond(issue(request.url.encodedPath.substringAfterLast('/')), HttpStatusCode.OK, json)
        }
        val provider = JiraEnrichmentProvider(testEngine = engine, timeSource = testTimeSource)
        provider.initialize(
            context(
                buildJsonObject {
                    put("url", "https://test.atlassian.net")
                    put("tickets", buildJsonObject { put("max-per-mr", 100) })
                    put("linked-issues", buildJsonObject { put("enabled", false) })
                },
            ),
        )

        provider.enrich(mr((1..count).joinToString(" ") { "PROJ-$it" }), JsonObject(emptyMap()))

        assertEquals(count, engine.requestHistory.size)
        provider.shutdown()
        return testScheduler.currentTime
    }

    @Test
    fun `the default limit lets a burst of 30 fetches through without waiting`() =
        runTest {
            assertEquals(0L, enrichKeys(30))
        }

    @Test
    fun `the 61st fetch within a minute waits once for the window`() =
        runTest {
            assertEquals(60_000L, enrichKeys(61))
        }

    @Test
    fun `acceptance criteria field is requested and parsed`() =
        runTest {
            val adf = """{"type":"doc","version":1,"content":[{"type":"bulletList","content":[""" +
                """{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Custom AC one"}]}]}]}]}"""
            val engine = MockEngine { respond(issue("PROJ-5", ""","customfield_10100":$adf"""), HttpStatusCode.OK, json) }
            val provider = JiraEnrichmentProvider(testEngine = engine)
            val config = buildJsonObject {
                put("url", "https://test.atlassian.net")
                put("acceptance-criteria", buildJsonObject { put("field", "customfield_10100") })
            }
            provider.initialize(context(config))

            val result = provider.enrich(mr("PROJ-5 thing"), JsonObject(emptyMap()))

            assertContains(
                engine.requestHistory
                    .single { "remotelink" !in it.url.encodedPath }
                    .url
                    .toString(),
                "customfield_10100"
            )
            val ac = result
                .getValue(EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA)
                .jsonObject
                .getValue("PROJ-5")
                .jsonArray
            assertEquals(listOf("Custom AC one"), ac.map { it.jsonPrimitive.content })
            provider.shutdown()
        }
}
