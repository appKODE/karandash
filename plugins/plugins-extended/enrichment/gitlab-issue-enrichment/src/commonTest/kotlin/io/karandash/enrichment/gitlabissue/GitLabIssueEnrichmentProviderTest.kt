package io.karandash.enrichment.gitlabissue

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

class GitLabIssueEnrichmentProviderTest {
    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()

        override fun debug(message: String) {}

        override fun info(message: String) {}

        override fun warn(message: String) {
            messages.add(message)
        }

        override fun error(message: String) {}
    }

    @Test
    fun warnsOnUnknownSubBlockKeyTypo() =
        runTest {
            val logger = CapturingLogger()
            val ctx = object : PluginContext {
                override val pluginConfig = buildJsonObject { put("issuess", buildJsonObject { put("enabled", true) }) }
                override val logger: Logger = logger
                override val hostVersion = "test"
                override val workingDirectory = "/tmp"

                override fun env(name: String): String? = null

                override fun warn(message: String) {}

                override fun log(message: String) {}
            }
            GitLabIssueEnrichmentProvider().initialize(ctx)

            assertTrue(logger.messages.any { it.contains("enrichment.gitlab-issue.issuess") && it.contains("issues") })
        }

    @Test
    fun noWarningsOnWellFormedConfig() =
        runTest {
            val logger = CapturingLogger()
            val ctx = object : PluginContext {
                override val pluginConfig = buildJsonObject {
                    put("project-id", 123)
                    put("token", "secret")
                    put("issues", buildJsonObject { put("enabled", true) })
                }
                override val logger: Logger = logger
                override val hostVersion = "test"
                override val workingDirectory = "/tmp"

                override fun env(name: String): String? = null

                override fun warn(message: String) {}

                override fun log(message: String) {}
            }
            GitLabIssueEnrichmentProvider().initialize(ctx)

            assertTrue(logger.messages.isEmpty(), "Expected no warnings, got: ${logger.messages}")
        }

    private fun authContext() =
        object : PluginContext {
            override val pluginConfig = buildJsonObject { put("project-id", 123) }
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = if (name == "GITLAB_TOKEN") "test-token" else null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private fun noTokenContext() =
        object : PluginContext {
            override val pluginConfig = buildJsonObject { put("project-id", 123) }
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private fun noProjectIdContext() =
        object : PluginContext {
            override val pluginConfig = JsonObject(emptyMap())
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = if (name == "GITLAB_TOKEN") "test-token" else null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private fun makeMR(
        title: String = "My MR",
        description: String? = null
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

    private fun issueJson(
        iid: Int = 7,
        title: String = "Fix crash",
        state: String = "opened"
    ) = """{"iid":$iid,"title":"$title","description":"Details","state":"$state","labels":[],"assignees":[]}"""

    // ── Metadata & lifecycle ─────────────────────────────────────────────────

    @Test
    fun moduleMetadata() {
        val provider = GitLabIssueEnrichmentProvider()
        assertEquals(GitLabIssueEnrichmentProvider.ID, provider.id)
        assertEquals(ModuleType.ENRICHMENT_PROVIDER, provider.type)
        assertFalse(provider.defaultEnabled)
        assertTrue(provider.providedEnrichment.contains(EnrichmentKeys.GITLAB_ISSUES))
        assertTrue(provider.requiredEnrichment().isEmpty())
    }

    @Test
    fun noTokenSkipsWithAReason() =
        runTest {
            val provider = GitLabIssueEnrichmentProvider()
            assertTrue(provider.initialize(noTokenContext()))
            assertTrue(provider.skipReason().orEmpty().contains("CI_JOB_TOKEN"), provider.skipReason())
        }

    @Test
    fun noProjectIdSkipsWithAReason() =
        runTest {
            val provider = GitLabIssueEnrichmentProvider()
            assertTrue(provider.initialize(noProjectIdContext()))
            assertTrue(provider.skipReason().orEmpty().contains("CI_PROJECT_ID"), provider.skipReason())
        }

    @Test
    fun shutdownWithoutInitializeDoesNotThrow() = runTest { GitLabIssueEnrichmentProvider().shutdown() }

    @Test
    fun shutdownIsIdempotent() =
        runTest {
            val provider = GitLabIssueEnrichmentProvider()
            provider.initialize(authContext())
            provider.shutdown()
            provider.shutdown()
        }

    // ── Enrichment ───────────────────────────────────────────────────────────

    @Test
    fun fetchesIssueReferencedByClosingKeyword() =
        runTest {
            val engine = MockEngine { respond(issueJson(7), HttpStatusCode.OK, jsonHeaders()) }
            val provider = GitLabIssueEnrichmentProvider(testEngine = engine)
            provider.initialize(authContext())

            val mr = makeMR(description = "Closes #7")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(EnrichmentKeys.GITLAB_ISSUES))
            val issues = result.getValue(EnrichmentKeys.GITLAB_ISSUES).jsonObject
            assertTrue(issues.containsKey("7"))
            assertEquals(
                "Fix crash",
                issues["7"]
                    ?.jsonObject
                    ?.get("title")
                    ?.jsonPrimitive
                    ?.content
            )
            provider.shutdown()
        }

    @Test
    fun returnsEmptyWhenNoIssueReferences() =
        runTest {
            val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders()) }
            val provider = GitLabIssueEnrichmentProvider(testEngine = engine)
            provider.initialize(authContext())

            val result = provider.enrich(makeMR(title = "Update README"), JsonObject(emptyMap()))
            assertTrue(result.isEmpty())
            provider.shutdown()
        }

    @Test
    fun returnsEmptyWhenIssueFetchFails() =
        runTest {
            val engine = MockEngine {
                respond("""{"message":"404 Not Found"}""", HttpStatusCode.NotFound, jsonHeaders())
            }
            val provider = GitLabIssueEnrichmentProvider(testEngine = engine)
            provider.initialize(authContext())

            val mr = makeMR(description = "Fixes #999")
            val result = provider.enrich(mr, JsonObject(emptyMap()))
            assertTrue(result.isEmpty())
            provider.shutdown()
        }
}

class GitLabIssueExtractorTest {
    @Test
    fun extractsClosingKeyword() {
        val result = GitLabIssueExtractor.extract(listOf("Closes #7"))
        assertTrue(result.contains(7))
    }

    @Test
    fun extractsFixesKeyword() {
        val result = GitLabIssueExtractor.extract(listOf("fixes #3"))
        assertTrue(result.contains(3))
    }

    @Test
    fun ignoresCrossProjectRef() {
        val result = GitLabIssueExtractor.extract(listOf("See group/project#12"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun extractsBareRef() {
        val result = GitLabIssueExtractor.extract(listOf("See #5 for details"))
        assertTrue(result.contains(5))
    }

    @Test
    fun deduplicatesAcrossTexts() {
        val result = GitLabIssueExtractor.extract(listOf("Closes #8", "Related to #8"))
        assertEquals(1, result.size)
        assertTrue(result.contains(8))
    }

    @Test
    fun returnsEmptyForPlainText() {
        val result = GitLabIssueExtractor.extract(listOf("No issue references here"))
        assertTrue(result.isEmpty())
    }
}
