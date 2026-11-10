package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.config.merge.MergeMode
import io.karandash.duration.KarandashDuration
import io.karandash.manifest.CachePolicy
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.ExtendsEntry
import io.karandash.manifest.RemoteFileResolution
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExtendsEntryResolverTest {
    private val evaluator = ConditionEvaluator(envProvider = { null }, platformName = "gitlab")

    @Test
    fun `url entries keep order - own merge strategy - file resolution and cache policy`() =
        runTest {
            val cache = CachePolicy(KarandashDuration.ofSeconds(60))
            val entries = listOf(
                ExtendsEntry.UrlEntry(ConfigSource(url = "a.yaml"), mergeStrategy = mapOf("labels" to MergeMode.APPEND)),
                ExtendsEntry.UrlEntry(ConfigSource(url = "b.yaml"), cache = cache, fileResolution = RemoteFileResolution.STRICT),
            )
            val resolved = ExtendsEntryResolver().resolveToSources(entries)

            assertEquals(listOf("a.yaml", "b.yaml"), resolved.map { it.source.url })
            assertEquals(mapOf("labels" to MergeMode.APPEND), resolved[0].mergeStrategy)
            assertEquals(null, resolved[1].mergeStrategy)
            assertEquals(cache, resolved[1].source.cachePolicy)
            assertEquals(RemoteFileResolution.STRICT, resolved[1].fileResolution)
        }

    @Test
    fun `entries whose if condition is false are skipped`() =
        runTest {
            val entries = listOf(
                ExtendsEntry.UrlEntry(ConfigSource(url = "gl.yaml"), condition = "platform == 'gitlab'"),
                ExtendsEntry.UrlEntry(ConfigSource(url = "gh.yaml"), condition = "platform == 'github'"),
            )
            val resolved = ExtendsEntryResolver(conditionEvaluator = evaluator).resolveToSources(entries)
            assertEquals(listOf("gl.yaml"), resolved.map { it.source.url })
        }

    @Test
    fun `entries needing a missing collaborator fail with a config error`() =
        runTest {
            val resolver = ExtendsEntryResolver()
            val cases = listOf(
                ExtendsEntry.UrlEntry(ConfigSource(url = "a.yaml"), condition = "platform == 'gitlab'"),
                ExtendsEntry.GlobEntry("base/*.yaml"),
                ExtendsEntry.ManifestRef("team"),
            )
            cases.forEach { entry ->
                assertFailsWith<ConfigException>(entry.toString()) { resolver.resolveToSources(listOf(entry)) }
            }
        }
}
