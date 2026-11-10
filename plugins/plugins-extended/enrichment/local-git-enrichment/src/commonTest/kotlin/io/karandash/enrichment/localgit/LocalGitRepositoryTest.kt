package io.karandash.enrichment.localgit

import io.karandash.api.PluginContext
import io.karandash.api.ai.BlockBudget
import io.karandash.api.cache.CacheProvider
import io.karandash.api.cache.KarandashCache
import io.karandash.api.platform.SubprocessBudget
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.platform.PlatformProcess
import io.karandash.sdk.Logger
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the analyzers and the provider against a real throwaway git repository.
 *
 * Every test returns early when `sh`, `mktemp` or `git` cannot build the repository, so a host
 * without them skips rather than fails.
 */
class LocalGitRepositoryTest {
    private class Ctx(
        override val workingDirectory: String,
        override val pluginConfig: JsonElement = JsonObject(emptyMap()),
        override val subprocessBudget: SubprocessBudget = SubprocessBudget.UNLIMITED,
        cache: KarandashCache? = null,
    ) : PluginContext {
        val warnings = mutableListOf<String>()
        override val hostVersion = "test"
        override val logger: Logger =
            object : Logger {
                override fun debug(message: String) = Unit

                override fun info(message: String) = Unit

                override fun warn(message: String) {
                    warnings += message
                }

                override fun error(message: String) = Unit
            }
        override val cacheProvider: CacheProvider =
            if (cache == null) {
                CacheProvider.NOOP
            } else {
                object : CacheProvider {
                    override val enabled = true

                    override fun getStringCache(
                        name: String,
                        maxEntries: Int,
                    ) = cache
                }
            }

        override fun env(name: String): String? = null

        override fun warn(message: String) {
            warnings += message
        }

        override fun log(message: String) = Unit
    }

    private class MapCache : KarandashCache {
        val entries = mutableMapOf<String, String>()
        val computedKeys = mutableListOf<String>()

        override suspend fun get(key: String) = entries[key]

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
        ) = getOrPut(key, emptyMap(), compute)

        override suspend fun getOrPut(
            key: String,
            dependencies: Map<String, String>,
            compute: suspend () -> String,
        ): String {
            val existing = entries[key]
            if (existing != null) return existing
            computedKeys += key
            return compute().also { entries[key] = it }
        }

        override suspend fun invalidate(key: String) {
            entries.remove(key)
        }

        override suspend fun clear() = entries.clear()

        override fun size() = entries.size
    }

    /** Three commits now (a.kt twice by Ann, b.kt by Bob) plus one old commit on a.kt from 2000. */
    private suspend fun repo(shallowMarker: Boolean = false): String? {
        val tmp = PlatformProcess.executeAsync(listOf("mktemp", "-d"))
        if (!tmp.isSuccess) return null
        val dir = tmp.output.trim()
        val script =
            """
            set -e
            git init -q .
            c() { GIT_AUTHOR_NAME="${'$'}1" GIT_AUTHOR_EMAIL="${'$'}2" GIT_COMMITTER_NAME=ci GIT_COMMITTER_EMAIL=ci@x.io \
              git -c commit.gpgsign=false -c core.hooksPath=/dev/null commit -q -m m; }
            echo 0 > a.kt; git add .
            export GIT_AUTHOR_DATE=2000-01-01T00:00:00 GIT_COMMITTER_DATE=2000-01-01T00:00:00
            c Old old@x.io
            unset GIT_AUTHOR_DATE GIT_COMMITTER_DATE
            echo 1 > a.kt; git add .; c Ann ann@x.io
            echo 2 > a.kt; echo 1 > b.kt; git add .; c Bob bob@x.io
            echo 3 > a.kt; git add .; c Ann ann@x.io
            ${if (shallowMarker) "git rev-parse HEAD > .git/shallow" else ""}
            """.trimIndent()
        val built = PlatformProcess.executeAsync(listOf("sh", "-c", script), workingDir = dir)
        return if (built.isSuccess) dir else null
    }

    private fun mr(vararg paths: String) =
        createTestMergeRequest(changes = paths.map { FileChange(path = it, changeType = ChangeType.MODIFIED) })

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    @Test
    fun `analyzers read frequencies and contributors from history`() =
        runTest {
            val dir = repo() ?: return@runTest

            assertEquals(
                mapOf("a.kt" to 3, "b.kt" to 1),
                FileFrequencyAnalyzer.analyze(dir, listOf("a.kt", "b.kt", "missing.kt")),
            )
            assertEquals(mapOf("a.kt" to 4), FileFrequencyAnalyzer.analyze(dir, listOf("a.kt"), period = "1999-01-01"))
            assertEquals(mapOf("a.kt" to 3), FileFrequencyAnalyzer.analyze(dir, listOf("a.kt", "b.kt"), maxPathspecs = 0))

            val contributors = ContributorAnalyzer.analyze(dir, listOf("a.kt", "b.kt"), chunkSize = 1)
            assertEquals(mapOf("ann@x.io" to 2, "bob@x.io" to 1), contributors.mapValues { it.value.commits })
            assertEquals("Ann", contributors.getValue("ann@x.io").name)
            assertTrue(ContributorAnalyzer.analyze("", listOf("a.kt")).isEmpty())
            assertTrue(ContributorAnalyzer.analyze(dir, emptyList()).isEmpty())
            assertTrue(FileFrequencyAnalyzer.analyze(null, listOf("a.kt")).isEmpty())
        }

    @Test
    fun `provider contributes frequency and contributor stats`() =
        runTest {
            val dir = repo() ?: return@runTest
            val provider = LocalGitEnrichmentProvider()
            provider.initialize(Ctx(dir))

            val result = provider.enrich(mr("a.kt", "b.kt"), obj("fileFrequency" to obj("period" to JsonPrimitive("1999-01-01"))))

            val frequency = assertNotNull(result[LocalGitEnrichmentProvider.ENRICHMENT_FILE_FREQUENCY]).jsonObject
            assertEquals("""{"a.kt":4,"b.kt":1}""", frequency.getValue("frequencies").toString())
            assertEquals("1999-01-01", frequency.getValue("period").jsonPrimitive.content)
            val stats = assertNotNull(result[LocalGitEnrichmentProvider.ENRICHMENT_CONTRIBUTOR_STATS]).jsonObject
            assertEquals(
                listOf("ann@x.io", "bob@x.io", "old@x.io"),
                stats.getValue("contributors").jsonArray.map {
                    it.jsonObject
                        .getValue("email")
                        .jsonPrimitive.content
                },
            )
        }

    @Test
    fun `provider returns nothing for files without history`() =
        runTest {
            val dir = repo() ?: return@runTest
            val provider = LocalGitEnrichmentProvider()
            provider.initialize(Ctx(dir))

            assertTrue(provider.enrich(mr("never.kt"), obj("period" to JsonPrimitive(" "))).isEmpty())
            assertTrue(provider.enrich(mr(), JsonObject(emptyMap())).isEmpty())
        }

    @Test
    fun `provider caches the computed result`() =
        runTest {
            val dir = repo() ?: return@runTest
            val cache = MapCache()
            val provider = LocalGitEnrichmentProvider()
            provider.initialize(Ctx(dir, cache = cache))

            val first = provider.enrich(mr("a.kt"), JsonObject(emptyMap()))
            val second = provider.enrich(mr("a.kt"), JsonObject(emptyMap()))

            assertEquals(first, second)
            assertEquals(1, cache.computedKeys.size)
            assertTrue(first.containsKey(LocalGitEnrichmentProvider.ENRICHMENT_FILE_FREQUENCY))
        }

    @Test
    fun `exhausted wall time budget warns about partial data`() =
        runTest {
            val dir = repo() ?: return@runTest
            // 1 ms halves to a 0 ms slice per analyzer, so git is skipped regardless of how fast the host runs it
            val context = Ctx(dir, pluginConfig = obj("subprocess-budget" to obj("max-wall-millis" to JsonPrimitive(1))))
            val provider = LocalGitEnrichmentProvider()
            provider.initialize(context)

            provider.enrich(mr("a.kt"), JsonObject(emptyMap()))

            assertTrue(
                context.warnings.any { it == "Local git enrichment: file frequency subprocess budget exhausted, data may be partial" },
                context.warnings.toString(),
            )
            assertTrue(
                context.warnings.any { it.startsWith("git helper: subprocess budget exhausted before running git log") },
                context.warnings.toString()
            )
        }

    @Test
    fun `helper skips git once the call budget is spent`() =
        runTest {
            val dir = repo() ?: return@runTest
            val warnings = mutableListOf<String>()
            val logger =
                object : Logger {
                    override fun debug(message: String) = Unit

                    override fun info(message: String) = Unit

                    override fun warn(message: String) {
                        warnings += message
                    }

                    override fun error(message: String) = Unit
                }
            val budget = SubprocessBudget(maxCalls = 1, maxWallMillis = 60_000)

            assertNotNull(GitCliHelper.run(dir, listOf("status"), budget = budget, logger = logger))
            assertNull(GitCliHelper.run(dir, listOf("status"), budget = budget, logger = logger))
            assertEquals(listOf("git helper: subprocess budget exhausted before running git status — skipping"), warnings)
        }

    @Test
    fun `context enricher runs legacy analysis unless the clone is shallow`() =
        runTest {
            val dir = repo() ?: return@runTest
            val enricher = LocalGitEnrichmentContextEnricher()
            enricher.initialize(Ctx(dir))
            val changes = listOf(FileChange("a.kt", ChangeType.MODIFIED), FileChange("b.kt", ChangeType.MODIFIED))

            val block = assertNotNull(enricher.provideBlock(mr(), changes, JsonObject(emptyMap()), BlockBudget.UNLIMITED))
            assertEquals(
                "Frequently changed files (last 6 months ago):\n  - a.kt: 3 commits\n  - b.kt: 1 commits",
                block.content,
            )
            assertNull(
                enricher.provideBlock(mr(), listOf(FileChange("none.kt", ChangeType.ADDED)), JsonObject(emptyMap()), BlockBudget.UNLIMITED)
            )

            assertFalse(ShallowCloneDetector.isShallow(dir))
            val shallow = repo(shallowMarker = true) ?: return@runTest
            assertTrue(ShallowCloneDetector.isShallow(shallow))
            val shallowEnricher = LocalGitEnrichmentContextEnricher()
            shallowEnricher.initialize(Ctx(shallow))
            assertNull(shallowEnricher.provideBlock(mr(), changes, JsonObject(emptyMap()), BlockBudget.UNLIMITED))
        }
}
