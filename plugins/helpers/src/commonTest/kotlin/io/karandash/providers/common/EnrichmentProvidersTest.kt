package io.karandash.providers.common

import io.karandash.api.PluginContext
import io.karandash.api.cache.CacheProvider
import io.karandash.api.cache.KarandashCache
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.platform.CIEnvironment
import io.karandash.helpers.output.OnError
import io.karandash.helpers.output.handle
import io.karandash.helpers.output.postOrFail
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnrichmentProvidersTest {
    private class MapCache : KarandashCache {
        val entries = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = entries[key]

        override suspend fun put(
            key: String,
            value: String,
            ttlMs: Long,
        ) {
            entries[key] = value
        }

        override suspend fun getOrPut(
            key: String,
            compute: suspend () -> String,
        ): String = entries.getOrPut(key) { compute() }

        override suspend fun getOrPut(
            key: String,
            dependencies: Map<String, String>,
            compute: suspend () -> String,
        ): String = entries.getOrPut("$key|$dependencies") { compute() }

        override suspend fun invalidate(key: String) {
            entries.remove(key)
        }

        override suspend fun clear() = entries.clear()

        override fun size(): Int = entries.size
    }

    private fun cachedContext(
        cache: KarandashCache,
        config: JsonElement = JsonObject(emptyMap()),
    ): PluginContext {
        val base = TestPluginContext(config)
        return object : PluginContext by base {
            override val cacheProvider = object : CacheProvider {
                override val enabled = true

                override fun getStringCache(
                    name: String,
                    maxEntries: Int,
                ) = cache
            }
        }
    }

    @Test
    fun `on error warn logs and swallows while fail logs and rethrows`() =
        runTest {
            val context = TestPluginContext()

            OnError.WARN.handle(context.logger, "send") { error("boom https://user:secret@host/x") }
            assertFailsWith<IllegalStateException> { OnError.FAIL.handle(context.logger, "send") { error("boom") } }
            OnError.FAIL.handle(context.logger, "send") { }

            assertEquals(2, context.warnings.size)
            assertTrue(context.warnings[0].startsWith("send: boom"), context.warnings[0])
            assertTrue(!context.warnings[0].contains("secret"), context.warnings[0])
        }

    @Test
    fun `cancellation passes through on error handling`() =
        runTest {
            val context = TestPluginContext()

            assertFailsWith<CancellationException> {
                OnError.WARN.handle(context.logger, "send") { throw CancellationException("stop") }
            }
            assertEquals(emptyList(), context.warnings)
        }

    @Test
    fun `post or fail throws with the status and a capped body`() =
        runTest {
            val client = HttpClient(
                MockEngine { respond("x".repeat(500), HttpStatusCode.BadGateway) },
            )

            val failure = assertFailsWith<IllegalStateException> { client.postOrFail("http://h/x", "hook") { } }

            assertEquals("hook: HTTP 502 " + "x".repeat(200), failure.message)
            HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }).postOrFail("http://h/x", "hook") { }
        }

    @Test
    fun `woodpecker builds are recognised only for the listed forges`() {
        val env = mapOf(
            "CI" to "woodpecker",
            "CI_FORGE_TYPE" to "gitea",
            "CI_REPO" to "o/r",
            "CI_COMMIT_PULL_REQUEST" to "7",
            "CI_FORGE_URL" to "https://git.example/",
        )

        val ci = ForgeCiEnvironment.woodpecker(env::get, "gitea", setOf("gitea", "forgejo")) { "$it/api" }

        assertEquals(CIEnvironment("gitea", "o/r", "7", null, "https://git.example/api"), ci)
        assertNull(ForgeCiEnvironment.woodpecker(env::get, "github", setOf("github")))
        assertNull(ForgeCiEnvironment.woodpecker({ null }, "gitea", setOf("gitea")))
    }

    @Test
    fun `code build recognises pull request builds by the webhook trigger or the source version`() =
        runTest {
            val projectOf: suspend (String) -> String? = { url -> url.removePrefix("https://github.com/").takeIf { it != url } }
            val base = mapOf("CODEBUILD_BUILD_ID" to "b:1", "CODEBUILD_SOURCE_REPO_URL" to "https://github.com/o/r")

            val trigger = ForgeCiEnvironment.codeBuild(
                (base + ("CODEBUILD_WEBHOOK_TRIGGER" to "pr/42"))::get,
                "github",
                "https://api",
                projectOf
            )
            val version = ForgeCiEnvironment.codeBuild((base + ("CODEBUILD_SOURCE_VERSION" to "pr/9"))::get, "github", null, projectOf)
            val branch = ForgeCiEnvironment.codeBuild(
                (base + ("CODEBUILD_WEBHOOK_TRIGGER" to "branch/main"))::get,
                "github",
                null,
                projectOf
            )

            assertEquals(CIEnvironment("github", "o/r", "42", null, "https://api"), trigger)
            assertEquals("9", version?.mrId)
            assertNull(branch?.mrId)
            assertNull(ForgeCiEnvironment.codeBuild({ null }, "github", null, projectOf))
            assertNull(
                ForgeCiEnvironment.codeBuild(
                    mapOf("CODEBUILD_BUILD_ID" to "b", "CODEBUILD_SOURCE_REPO_URL" to "https://elsewhere/x")::get,
                    "github",
                    null,
                    projectOf,
                )
            )
            assertNull(ForgeCiEnvironment.codeBuild(mapOf("CODEBUILD_BUILD_ID" to "b")::get, "github", null, projectOf))
        }

    @Test
    fun `credentials fall back to the default and skip blank values`() {
        val decoded = CredentialsSupport.decodeOrDefault(
            buildJsonObject {
                put("a", "x")
            },
            kotlinx.serialization.json.JsonObject
                .serializer()
        ) { JsonObject(emptyMap()) }
        val fallback = CredentialsSupport.decodeOrDefault(JsonNull, String.serializer()) { "dflt" }
        val env = mapOf("A" to " ", "B" to "tok")

        assertEquals(setOf("a"), decoded.keys)
        assertEquals("dflt", fallback)
        assertEquals("cfg", CredentialsSupport.firstNonBlank("cfg", env::get, "B"))
        assertEquals("tok", CredentialsSupport.firstNonBlank(" ", env::get, "A", "B"))
        assertNull(CredentialsSupport.firstNonBlank(null, env::get, "A", "MISSING"))
    }

    @Test
    fun `fetch or null logs the failure and returns null`() {
        val logged = mutableListOf<String>()

        assertEquals(5, fetchOrNull("get", logged::add) { 5 })
        assertNull(fetchOrNull("get", logged::add) { error("down") })
        assertEquals(listOf("get: down"), logged)
    }

    @Test
    fun `cached enrichment reuses the result for unchanged dependencies`() =
        runTest {
            val cached = CachedEnrichment(cachedContext(MapCache()), "n", 60)
            var computed = 0
            val compute: suspend () -> Map<String, JsonElement> = {
                computed++
                mapOf("k" to JsonPrimitive("v"))
            }

            val first = cached.enrich(mapOf("title" to "a"), compute)
            val second = cached.enrich(mapOf("title" to "a"), compute)
            cached.enrich(mapOf("title" to "b"), compute)

            assertEquals(mapOf("k" to JsonPrimitive("v")), first)
            assertEquals(first, second)
            assertEquals(2, computed)
        }

    @Test
    fun `merge request text dependencies carry title description branch and commit messages`() {
        val mr = createTestMergeRequest(
            title = "T",
            description = null,
            sourceBranch = "feat",
            commits = listOf(
                Commit("1", "one", CommitAuthor("n", "e"), "2024-01-01T00:00:00Z"),
                Commit("2", "two", CommitAuthor("n", "e"), "2024-01-01T00:00:00Z"),
            ),
        )

        assertEquals(
            mapOf("title" to "T", "description" to "", "sourceBranch" to "feat", "commits" to "one,two", "x" to "y"),
            CachedEnrichment.mrTextDependencies(mr, "x" to "y"),
        )
    }

    private class FakeIssueClient(
        private val issues: Map<Int, JsonElement>,
    ) : IssueClient {
        val fetched = mutableListOf<Int>()
        var closed = false

        override suspend fun fetchIssueJson(number: Int): JsonElement? {
            fetched += number
            return issues[number]
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeIssueProvider(
        private val client: FakeIssueClient,
        private val skip: String? = null,
    ) : AbstractIssueEnrichmentProvider("Fake issue enrichment", "fakeIssues") {
        override val id = "test.fake"
        override val name = "Fake"
        override val version = "1"

        override fun decode(config: JsonElement): IssueSubConfig? =
            runCatching { KarandashJson.Default.decodeFromJsonElement(IssueSubConfig.serializer(), config) }.getOrNull()

        override suspend fun connect(context: PluginContext): String? {
            if (skip != null) return skip
            connectClient("key") { client }
            return null
        }

        override fun extract(texts: List<String>): Collection<Int> =
            texts.flatMap { Regex("#(\\d+)").findAll(it).map { m -> m.groupValues[1].toInt() }.toList() }.distinct()
    }

    private fun config(
        enabled: Boolean = true,
        max: Int = 10,
    ) = buildJsonObject {
        put("enabled", enabled)
        put("max-issues-per-mr", max)
    }

    @Test
    fun `an issue provider fetches the referenced issues and publishes them by number`() =
        runTest {
            val client = FakeIssueClient(mapOf(1 to JsonPrimitive("one"), 2 to JsonPrimitive("two")))
            val provider = FakeIssueProvider(client)
            val context = TestPluginContext(config())
            val mr = createTestMergeRequest(
                title = "fix #1",
                description = "see #2 and #3",
                commits = listOf(Commit("s", "also #1", CommitAuthor("n", "e"), "t")),
            )

            assertTrue(provider.initialize(context))
            val out = provider.enrich(mr, JsonObject(emptyMap()), EnrichmentContext(emptyMap()))

            assertEquals(setOf("fakeIssues"), provider.providedEnrichment)
            assertEquals(emptyList(), provider.requiredEnrichment())
            assertNull(provider.skipReason())
            assertEquals(listOf(1, 2, 3), client.fetched)
            assertEquals(mapOf("fakeIssues" to JsonObject(mapOf("1" to JsonPrimitive("one"), "2" to JsonPrimitive("two")))), out)
            provider.shutdown()
            assertTrue(client.closed)
        }

    @Test
    fun `an issue provider caps the fetches and returns nothing without references or hits`() =
        runTest {
            val client = FakeIssueClient(mapOf(1 to JsonPrimitive("one")))
            val provider = FakeIssueProvider(client)
            provider.initialize(TestPluginContext(config(max = 1)))

            provider.enrich(createTestMergeRequest(title = "#1 #2"), JsonObject(emptyMap()), EnrichmentContext(emptyMap()))
            val none = provider.enrich(createTestMergeRequest(title = "nothing"), JsonObject(emptyMap()), EnrichmentContext(emptyMap()))
            val miss = FakeIssueProvider(FakeIssueClient(emptyMap())).run {
                initialize(TestPluginContext(config()))
                enrich(createTestMergeRequest(title = "#5"), JsonObject(emptyMap()), EnrichmentContext(emptyMap()))
            }

            assertEquals(listOf(1), client.fetched)
            assertEquals(emptyMap(), none)
            assertEquals(emptyMap(), miss)
        }

    @Test
    fun `an issue provider reports bad config disabled and skipped states`() =
        runTest {
            val bad = FakeIssueProvider(FakeIssueClient(emptyMap()))
            val disabled = FakeIssueProvider(FakeIssueClient(emptyMap()))
            val skipped = FakeIssueProvider(FakeIssueClient(emptyMap()), skip = "no token")
            val context = TestPluginContext(config())

            assertEquals(false, bad.initialize(TestPluginContext(JsonPrimitive("nope"))))
            assertEquals(false, disabled.initialize(TestPluginContext(config(enabled = false))))
            assertTrue(skipped.initialize(context))
            assertEquals("no token", skipped.skipReason())
            assertEquals(listOf("Fake issue enrichment: no token"), context.warnings)
            assertEquals(
                emptyMap(),
                skipped.enrich(createTestMergeRequest(title = "#1"), JsonObject(emptyMap()), EnrichmentContext(emptyMap())),
            )
        }

    @Test
    fun `an issue provider with the run cache on serves a repeated merge request from the cache`() =
        runTest {
            val client = FakeIssueClient(mapOf(1 to JsonPrimitive("one")))
            val provider = FakeIssueProvider(client)
            provider.initialize(cachedContext(MapCache(), config()))
            val mr = createTestMergeRequest(title = "#1")

            val first = provider.enrich(mr, JsonObject(emptyMap()), EnrichmentContext(emptyMap()))
            val second = provider.enrich(mr, JsonObject(emptyMap()), EnrichmentContext(emptyMap()))

            assertEquals(first, second)
            assertEquals(listOf(1), client.fetched)
        }

    @Test
    fun `adapter helpers build history blame suggestions and latest per reviewer`() {
        val blame = AdapterHelpers.historyBlame("abc", "Ann", "a@x", "msg", "2024-01-01T00:00:00Z")

        assertEquals(0, blame.startLine)
        assertEquals(0, blame.endLine)
        assertEquals("abc", blame.commit.sha)
        assertEquals("Ann", blame.commit.author.name)
        assertEquals("```suggestion\nx = 1\n```", AdapterHelpers.renderSuggestion("x = 1"))
        assertEquals(
            listOf("a2", "b1"),
            AdapterHelpers.latestPerReviewer(listOf("a1", "b1", "a2"), reviewer = { it.take(1) }),
        )
        assertEquals(
            listOf("a1", "b1"),
            AdapterHelpers.latestPerReviewer(listOf("a1", "b1", "a2"), reviewer = { it.take(1) }, latest = { it.first() }),
        )
    }

    @Test
    fun `superseding marks only pending comments in scope`() =
        runTest {
            val context = TestPluginContext()
            val marked = mutableListOf<String>()
            val items = listOf("a.kt" to "<!-- m --> old", "b.kt" to "<!-- m --> old", "a.kt" to "other")

            val count = SupersededInlineComment.supersede(
                items = items,
                marker = "m",
                filePaths = setOf("a.kt"),
                logger = context.logger,
                body = { it.second },
                path = { it.first },
                mark = { marked += it.first },
            )

            assertEquals(1, count)
            assertEquals(listOf("a.kt"), marked)
        }
}
