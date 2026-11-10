package io.karandash.enrichment.github

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubIssueEnrichmentProviderTest {
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
            GitHubIssueEnrichmentProvider().initialize(ctx)

            assertTrue(logger.messages.any { it.contains("enrichment.github-issue.issuess") && it.contains("issues") })
        }

    @Test
    fun noWarningsOnWellFormedConfig() =
        runTest {
            val logger = CapturingLogger()
            val ctx = object : PluginContext {
                override val pluginConfig = buildJsonObject {
                    put("owner", "acme")
                    put("repo", "backend")
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
            GitHubIssueEnrichmentProvider().initialize(ctx)

            assertTrue(logger.messages.isEmpty(), "Expected no warnings, got: ${logger.messages}")
        }

    private fun authContext() =
        object : PluginContext {
            override val pluginConfig = buildJsonObject {
                put("owner", "acme")
                put("repo", "backend")
            }
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = if (name == "GITHUB_TOKEN") "test-token" else null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private fun noTokenContext() =
        object : PluginContext {
            override val pluginConfig = buildJsonObject {
                put("owner", "acme")
                put("repo", "backend")
            }
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private fun noOwnerContext() =
        object : PluginContext {
            override val pluginConfig = JsonObject(emptyMap())
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = if (name == "GITHUB_TOKEN") "test-token" else null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private fun makeMR(
        title: String = "My PR",
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
        number: Int = 42,
        title: String = "Fix login bug",
        state: String = "open",
    ) = """{"number":$number,"title":"$title","body":"Description here","state":"$state","labels":[],"assignees":[]}"""

    // ── Metadata & lifecycle ─────────────────────────────────────────────────

    @Test
    fun moduleMetadata() {
        val provider = GitHubIssueEnrichmentProvider()
        assertEquals(GitHubIssueEnrichmentProvider.ID, provider.id)
        assertEquals(ModuleType.ENRICHMENT_PROVIDER, provider.type)
        assertFalse(provider.defaultEnabled)
        assertTrue(provider.providedEnrichment.contains(EnrichmentKeys.GITHUB_ISSUES))
        assertTrue(provider.requiredEnrichment().isEmpty())
    }

    @Test
    fun noTokenSkipsWithAReason() =
        runTest {
            val provider = GitHubIssueEnrichmentProvider()
            assertTrue(provider.initialize(noTokenContext()))
            assertTrue(provider.skipReason().orEmpty().contains("GITHUB_TOKEN"), provider.skipReason())
        }

    @Test
    fun noOwnerSkipsWithAReason() =
        runTest {
            val provider = GitHubIssueEnrichmentProvider()
            assertTrue(provider.initialize(noOwnerContext()))
            assertTrue(provider.skipReason().orEmpty().contains("GITHUB_REPOSITORY"), provider.skipReason())
        }

    @Test
    fun shutdownWithoutInitializeDoesNotThrow() = runTest { GitHubIssueEnrichmentProvider().shutdown() }

    @Test
    fun shutdownIsIdempotent() =
        runTest {
            val provider = GitHubIssueEnrichmentProvider()
            provider.initialize(authContext())
            provider.shutdown()
            provider.shutdown()
        }

    // ── Enrichment ───────────────────────────────────────────────────────────

    @Test
    fun fetchesIssueReferencedByClosingKeyword() =
        runTest {
            val engine = MockEngine { respond(issueJson(42), HttpStatusCode.OK, jsonHeaders()) }
            val provider = GitHubIssueEnrichmentProvider(testEngine = engine)
            provider.initialize(authContext())

            val mr = makeMR(description = "Closes #42")
            val result = provider.enrich(mr, JsonObject(emptyMap()))

            assertTrue(result.containsKey(EnrichmentKeys.GITHUB_ISSUES))
            val issues = result.getValue(EnrichmentKeys.GITHUB_ISSUES).jsonObject
            assertTrue(issues.containsKey("42"))
            assertEquals(
                "Fix login bug",
                issues["42"]
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
            val provider = GitHubIssueEnrichmentProvider(testEngine = engine)
            provider.initialize(authContext())

            val result = provider.enrich(makeMR(title = "Update README"), JsonObject(emptyMap()))
            assertTrue(result.isEmpty())
            provider.shutdown()
        }

    @Test
    fun returnsEmptyWhenIssueFetchFails() =
        runTest {
            val engine = MockEngine {
                respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, jsonHeaders())
            }
            val provider = GitHubIssueEnrichmentProvider(testEngine = engine)
            provider.initialize(authContext())

            val mr = makeMR(description = "Fixes #999")
            val result = provider.enrich(mr, JsonObject(emptyMap()))
            assertTrue(result.isEmpty())
            provider.shutdown()
        }
}

class GitHubIssueExtractorTest {
    @Test
    fun extractsClosingKeyword() {
        val result = GitHubIssueExtractor.extract(listOf("Closes #42"), REPO)
        assertTrue(result.contains(42))
    }

    @Test
    fun extractsFixesKeyword() {
        val result = GitHubIssueExtractor.extract(listOf("fixes #7"), REPO)
        assertTrue(result.contains(7))
    }

    @Test
    fun extractsResolvesKeyword() {
        val result = GitHubIssueExtractor.extract(listOf("Resolves #100"), REPO)
        assertTrue(result.contains(100))
    }

    @Test
    fun extractsCrossRepoRef() {
        val result = GitHubIssueExtractor.extract(listOf("See owner/repo#55 and other/repo#56"), "Owner/Repo")
        assertEquals(setOf(55), result)
    }

    @Test
    fun extractsBareRef() {
        val result = GitHubIssueExtractor.extract(listOf("See #12 for details"), REPO)
        assertTrue(result.contains(12))
    }

    @Test
    fun deduplicatesAcrossTexts() {
        val result = GitHubIssueExtractor.extract(listOf("Closes #5", "Also closes #5"), REPO)
        assertEquals(1, result.size)
        assertTrue(result.contains(5))
    }

    @Test
    fun returnsEmptyForPlainText() {
        val result = GitHubIssueExtractor.extract(listOf("No references here"), REPO)
        assertTrue(result.isEmpty())
    }

    @Test
    fun doesNotMatchUrlFragments() {
        val result = GitHubIssueExtractor.extract(listOf("https://example.com/path#section"), REPO)
        assertNull(result.firstOrNull())
    }

    private companion object {
        const val REPO = "acme/backend"
    }
}
