package io.karandash.enrichment.confluence

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfluenceEnrichmentProviderTest {
    private val confluenceBase = "https://test.atlassian.net/wiki"

    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()

        override fun debug(message: String) {}

        override fun info(message: String) {}

        override fun warn(message: String) {
            messages.add(message)
        }

        override fun error(message: String) {}
    }

    private fun contextWithConfig(
        config: JsonObject,
        logger: Logger,
    ) = object : PluginContext {
        override val pluginConfig = config
        override val logger: Logger = logger
        override val hostVersion = "test"
        override val workingDirectory = "/tmp"

        override fun env(name: String): String? = null

        override fun warn(message: String) {}

        override fun log(message: String) {}
    }

    @Test
    fun warnsOnUnknownSubBlockKeyTypo() =
        runTest {
            val logger = CapturingLogger()
            val config = buildJsonObject { put("pagess", buildJsonObject { put("enabled", true) }) }
            ConfluenceEnrichmentProvider().initialize(contextWithConfig(config, logger))

            assertTrue(logger.messages.any { it.contains("enrichment.confluence.pagess") && it.contains("pages") })
        }

    @Test
    fun noWarningsOnWellFormedConfig() =
        runTest {
            val logger = CapturingLogger()
            val config = buildJsonObject {
                put("url", confluenceBase)
                put("token", "secret")
                put("pages", buildJsonObject { put("enabled", true) })
            }
            ConfluenceEnrichmentProvider().initialize(contextWithConfig(config, logger))

            assertTrue(logger.messages.isEmpty(), "Expected no warnings, got: ${logger.messages}")
        }

    private fun noAuthContext() =
        object : PluginContext {
            override val pluginConfig = JsonObject(emptyMap())
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private fun authContext(
        url: String = confluenceBase,
        allowLocalhost: Boolean = false,
    ) = object : PluginContext {
        override val pluginConfig = buildJsonObject { put("url", url) }
        override val allowLocalhost = allowLocalhost
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

    private fun makeMR(
        title: String = "My MR",
        description: String? = null,
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = title,
        description = description,
        author = User(username = "alice", name = "Alice"),
        sourceBranch = "feature/test",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        changes = emptyList(),
        commits = emptyList(),
        approvals = emptyList(),
        reviewers = emptyList(),
    )

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun pageJson(
        id: String = "123456",
        title: String = "Design Doc",
        body: String = "<p>Content</p>",
    ) = """
        {
          "id": "$id",
          "title": "$title",
          "version": { "number": 1 },
          "body": { "storage": { "value": "$body" } },
          "_links": { "webui": "/spaces/ENG/pages/$id" }
        }
        """.trimIndent()

    // ── Metadata & lifecycle ─────────────────────────────────────────────────

    @Test
    fun moduleMetadata() {
        val provider = ConfluenceEnrichmentProvider()
        assertEquals(ConfluenceEnrichmentProvider.ID, provider.id)
        assertEquals(ModuleType.ENRICHMENT_PROVIDER, provider.type)
        assertFalse(provider.defaultEnabled)
        assertTrue(provider.providedEnrichment.contains(EnrichmentKeys.CONFLUENCE_PAGES))
        assertTrue(provider.requiredEnrichment().contains(EnrichmentKeys.JIRA_LINKED_ISSUES))
    }

    @Test
    fun initializeReturnsFalseWhenNoUrl() =
        runTest {
            assertFalse(ConfluenceEnrichmentProvider().initialize(noAuthContext()))
        }

    @Test
    fun noTokenSkipsWithAReason() =
        runTest {
            val ctx =
                object : PluginContext {
                    override val pluginConfig = buildJsonObject { put("url", confluenceBase) }
                    override val logger: Logger = NoOpLogger
                    override val hostVersion = "test"
                    override val workingDirectory = "/tmp"

                    override fun env(name: String): String? = null

                    override fun warn(message: String) {}

                    override fun log(message: String) {}
                }
            val provider = ConfluenceEnrichmentProvider()
            assertTrue(provider.initialize(ctx))
            assertTrue(provider.skipReason().orEmpty().contains("CONFLUENCE_TOKEN"), provider.skipReason())
        }

    @Test
    fun shutdownWithoutInitializeDoesNotThrow() = runTest { ConfluenceEnrichmentProvider().shutdown() }

    @Test
    fun shutdownIsIdempotent() =
        runTest {
            val provider = ConfluenceEnrichmentProvider()
            provider.initialize(authContext())
            provider.shutdown()
            provider.shutdown()
        }

    // ── Enrichment ───────────────────────────────────────────────────────────

    @Test
    fun fetchesPagesReferencedInMrDescription() =
        runTest {
            val engine = MockEngine { respond(pageJson("123456"), HttpStatusCode.OK, jsonHeaders()) }
            val provider = ConfluenceEnrichmentProvider(testEngine = engine)
            provider.initialize(authContext())

            val mr = makeMR(
                description = "See $confluenceBase/wiki/spaces/ENG/pages/123456/My+Page",
            )
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(EnrichmentKeys.CONFLUENCE_PAGES))
            val pages = result.getValue(EnrichmentKeys.CONFLUENCE_PAGES).jsonObject
            assertTrue(pages.containsKey("123456"))
            assertEquals(
                "Design Doc",
                pages["123456"]
                    ?.jsonObject
                    ?.get("title")
                    ?.jsonPrimitive
                    ?.content
            )
            provider.shutdown()
        }

    @Test
    fun localhostConfluenceIsReachedOnlyWithAllowLocalhost() =
        runTest {
            val base = "http://localhost:8080/confluence"
            val engine = MockEngine { respond(pageJson("123456"), HttpStatusCode.OK, jsonHeaders()) }
            val mr = makeMR(description = "See $base/wiki/spaces/ENG/pages/123456/My+Page")

            val blocked = ConfluenceEnrichmentProvider(testEngine = engine)
            blocked.initialize(authContext(url = base))
            assertTrue(blocked.enrich(mr, JsonObject(emptyMap())).isEmpty())
            blocked.shutdown()

            val allowed = ConfluenceEnrichmentProvider(testEngine = engine)
            allowed.initialize(authContext(url = base, allowLocalhost = true))
            assertTrue(allowed.enrich(mr, JsonObject(emptyMap())).containsKey(EnrichmentKeys.CONFLUENCE_PAGES))
            allowed.shutdown()
        }

    @Test
    fun returnsEmptyWhenNoConfluenceUrlsFound() =
        runTest {
            val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders()) }
            val provider = ConfluenceEnrichmentProvider(testEngine = engine)
            provider.initialize(authContext())

            val result = provider.enrich(makeMR(title = "No links here"), JsonObject(emptyMap()))
            assertTrue(result.isEmpty())
            provider.shutdown()
        }

    @Test
    fun returnsEmptyWhenPageFetchFails() =
        runTest {
            val engine = MockEngine {
                respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, jsonHeaders())
            }
            val provider = ConfluenceEnrichmentProvider(testEngine = engine)
            provider.initialize(authContext())

            val mr = makeMR(
                description = "$confluenceBase/wiki/spaces/ENG/pages/999/Gone",
            )
            val result = provider.enrich(mr, JsonObject(emptyMap()))
            assertTrue(result.isEmpty())
            provider.shutdown()
        }
}
