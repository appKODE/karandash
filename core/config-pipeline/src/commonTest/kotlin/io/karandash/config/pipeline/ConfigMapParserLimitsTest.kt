package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.cache.DownloadsCacheConfig
import io.karandash.duration.KarandashDuration
import io.karandash.manifest.ConfigSource
import io.karandash.sdk.config.anyToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `limits:` / `display:` parsing, inherited `limits` and the bounds wired into chain resolution. */
class ConfigMapParserLimitsTest {
    @Test
    fun `parseFullConfig reads limits and display in kebab case`() {
        val raw = anyToJsonElement(
            mapOf(
                "limits" to mapOf(
                    "max-findings" to 500,
                    "fetch-timeout" to "30s",
                    "expressions" to mapOf("max-length" to 200),
                    "patterns" to mapOf("max-expansion-depth" to 7),
                ),
                "display" to mapOf(
                    "watcher" to mapOf("debounce" to "2s"),
                    "console" to mapOf("success-mark" to "OK"),
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(raw).config

        assertEquals(500, config.limits.effectiveMaxFindings)
        assertEquals(KarandashDuration.ofSeconds(30), config.limits.effectiveFetchTimeout)
        assertEquals(200, config.limits.expressions.effectiveMaxLength)
        assertEquals(7, config.limits.patterns.effectiveMaxExpansionDepth)
        assertEquals(KarandashDuration.ofSeconds(2), config.display.watcher.effectiveDebounce)
        assertEquals("OK", config.display.console.successMark)
    }

    @Test
    fun `out of range values are clamped and warned instead of failing the config`() {
        val raw = anyToJsonElement(
            mapOf(
                "limits" to mapOf("max-findings" to 1, "extends" to mapOf("max-depth" to 1000)),
                "display" to mapOf("git" to mapOf("short-sha-length" to 99)),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(raw).config

        assertEquals(100, config.limits.effectiveMaxFindings)
        assertEquals(20, config.limits.extends.effectiveMaxDepth)
        assertEquals(40, config.display.git.effectiveShortShaLength)
        val warnings = config.limits.boundsWarnings() + config.display.boundsWarnings()
        assertEquals(3, warnings.size, "$warnings")
        assertTrue(warnings.any { "limits.maxFindings" in it && "using 100" in it }, "$warnings")
    }

    @Test
    fun `unset limits keep the built-in defaults and warn nothing`() {
        val config = ConfigMapParser.parseFullConfig(anyToJsonElement(emptyMap<String, Any?>())).config
        assertEquals(10_000, config.limits.effectiveMaxFindings)
        assertEquals(ConfigChainResolver.MAX_DEPTH, config.limits.extends.effectiveMaxDepth)
        assertTrue(config.limits.boundsWarnings().isEmpty())
        assertTrue(config.display.boundsWarnings().isEmpty())
    }

    @Test
    fun `parseRootLimits reads the root limits block only`() {
        val root = anyToJsonElement(mapOf("limits" to mapOf("extends" to mapOf("max-entries" to 2))))
        assertEquals(2, ConfigMapParser.parseRootLimits(root).extends.effectiveMaxEntries)
        assertEquals(10, ConfigMapParser.parseRootLimits(null).extends.effectiveMaxEntries)
    }

    @Test
    fun `parseRootDownloadsCache reads the root cache downloads block in kebab case`() {
        val root = anyToJsonElement(mapOf("cache" to mapOf("downloads" to mapOf("enabled" to false, "ttl" to "2h"))))
        val downloads = ConfigMapParser.parseRootDownloadsCache(root)
        assertFalse(downloads.enabled)
        assertEquals(KarandashDuration.ofHours(2), downloads.ttl)
        assertEquals(DownloadsCacheConfig(), ConfigMapParser.parseRootDownloadsCache(null))
    }

    @Test
    fun `bootstrap enforces the root maxEntries`() {
        val root = anyToJsonElement(mapOf("extends" to listOf("a.yaml", "b.yaml", "c.yaml")))
        assertEquals(3, ConfigMapParser.parseBootstrapConfig(root).extends.size)
        assertFailsWith<ConfigException> { ConfigMapParser.parseBootstrapConfig(root, maxExtendsEntries = 2) }
    }

    @Test
    fun `parent limits extends fetchTimeout and glob inherit like any other key and the root wins`() =
        runTest {
            val layers = mapOf(
                "parent" to mapOf(
                    "limits" to mapOf(
                        "extends" to mapOf("max-depth" to 7),
                        "fetch-timeout" to "120s",
                        "glob" to mapOf("max-expansion" to 3),
                        "max-findings" to 200,
                    ),
                ),
                "root" to mapOf("limits" to mapOf("fetch-timeout" to "5s")),
            )
            val module = object : ConfigFormatModule {
                override val formatId = "yaml"

                override suspend fun parseToJsonElement(content: String): JsonElement = anyToJsonElement(layers.getValue(content))
            }
            val warnings = mutableListOf<String>()
            val parser = ConfigChainParser(ConfigFormatRegistry(listOf(module)), onWarning = { warnings += it })
            val chain = ConfigChain(
                listOf(entry("https://example.com/parent.yaml", "parent", depth = 1), entry("file://config.yaml", "root", depth = 0)),
            )
            val merged = parser.parseAndMerge(chain) as JsonObject
            val config = ConfigMapParser.parseFullConfig(merged as JsonElement).config

            assertTrue(warnings.isEmpty(), "$warnings")
            assertEquals(7, config.limits.extends.effectiveMaxDepth)
            assertEquals(3, config.limits.glob.effectiveMaxExpansion)
            assertEquals(KarandashDuration.ofSeconds(5), config.limits.effectiveFetchTimeout)
            assertEquals(200, config.limits.effectiveMaxFindings)
        }

    private fun entry(
        url: String,
        content: String,
        depth: Int,
    ) = ChainEntry(ConfigSource(url), depth = depth, mergeStrategy = null, content = content, format = "yaml")

    @Test
    fun `a bad value inside a typed block is a ConfigParseException naming the block`() {
        val raw = anyToJsonElement(mapOf("cache" to mapOf("responses" to mapOf("ttl" to "abc"))))
        val e = assertFailsWith<ConfigParseException> { ConfigMapParser.parseFullConfig(raw) }
        assertTrue("'cache'" in e.message.orEmpty(), e.message)
        assertTrue("abc" in e.message.orEmpty(), e.message)
    }
}
