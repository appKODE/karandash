package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.exception.ConfigParseException
import io.karandash.config.MergeStrategy
import io.karandash.config.merge.MergeMode
import io.karandash.duration.KarandashDuration
import io.karandash.http.HttpAuth
import io.karandash.manifest.ExtendsEntry
import io.karandash.manifest.RemoteFileResolution
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtendsParserTest {
    private fun extendsOf(value: JsonElement): JsonObject = JsonObject(mapOf("extends" to value))

    private fun urlEntryWithCache(ttl: JsonElement?): ExtendsEntry.UrlEntry {
        val entry = buildJsonObject {
            put("url", "https://example.com/base.yaml")
            put("cache", JsonObject(if (ttl == null) emptyMap() else mapOf("ttl" to ttl)))
        }
        return assertIs<ExtendsEntry.UrlEntry>(ExtendsParser.parse(extendsOf(entry), emptyMap()).single())
    }

    @Test
    fun `ttl -1 means never expire`() {
        assertEquals(KarandashDuration.NEVER, urlEntryWithCache(JsonPrimitive(-1)).cache?.ttl)
        assertEquals(KarandashDuration.NEVER, urlEntryWithCache(JsonPrimitive("-1")).cache?.ttl)
        assertEquals(KarandashDuration.NEVER, urlEntryWithCache(JsonPrimitive("never")).cache?.ttl)
    }

    @Test
    fun `ttl 0 disables caching and a number is seconds`() {
        assertEquals(KarandashDuration.ZERO, urlEntryWithCache(JsonPrimitive(0)).cache?.ttl)
        assertEquals(KarandashDuration.ofSeconds(300), urlEntryWithCache(JsonPrimitive(300)).cache?.ttl)
        assertEquals(KarandashDuration.ofMinutes(5), urlEntryWithCache(JsonPrimitive("5m")).cache?.ttl)
    }

    @Test
    fun `a bad cache ttl is a ConfigParseException`() {
        val e = assertFailsWith<ConfigParseException> { urlEntryWithCache(JsonPrimitive("soon")) }
        assertTrue("cache.ttl" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `cache block without ttl uses the one hour default and no block means no policy`() {
        assertEquals(KarandashDuration.ofSeconds(3600), urlEntryWithCache(null).cache?.ttl)
        val plain = ExtendsParser.parse(extendsOf(buildJsonObject { put("url", "https://example.com/a.yaml") }), emptyMap()).single()
        assertNull(assertIs<ExtendsEntry.UrlEntry>(plain).cache)
    }

    @Test
    fun `string forms map to url and glob and manifest entries`() {
        val entries = ExtendsParser.parse(
            extendsOf(
                buildJsonArray {
                    add(JsonPrimitive("https://example.com/base.yaml"))
                    add(JsonPrimitive("configs/*.yaml"))
                    add(JsonPrimitive("configs/{a,b}.yaml"))
                    add(JsonPrimitive("@standard"))
                },
            ),
            emptyMap(),
        )
        assertEquals("https://example.com/base.yaml", assertIs<ExtendsEntry.UrlEntry>(entries[0]).source.url)
        assertEquals("configs/*.yaml", assertIs<ExtendsEntry.GlobEntry>(entries[1]).pattern)
        assertEquals("configs/{a,b}.yaml", assertIs<ExtendsEntry.GlobEntry>(entries[2]).pattern)
        assertEquals("standard", assertIs<ExtendsEntry.ManifestRef>(entries[3]).name)
    }

    @Test
    fun `object form carries condition and merge strategy and file resolution`() {
        val entry = buildJsonObject {
            put("url", "https://example.com/base.yaml")
            put("if", "is_draft")
            put("file-resolution", "fallback-local")
            put("merge-strategy", buildJsonObject { put("rules", "replace") })
        }
        val parsed = assertIs<ExtendsEntry.UrlEntry>(ExtendsParser.parse(extendsOf(entry), emptyMap()).single())
        assertEquals("is_draft", parsed.condition)
        assertEquals(RemoteFileResolution.FALLBACK_LOCAL, parsed.fileResolution)
        assertEquals(mapOf("rules" to MergeMode.REPLACE), parsed.mergeStrategy)
    }

    @Test
    fun `an env var condition stays raw so an unset variable still compares`() {
        val entry = buildJsonObject {
            put("url", "https://example.com/base.yaml")
            put("if", "\${KARANDASH_TEST_UNSET_ENV} == 'production'")
        }
        val condition = assertIs<ExtendsEntry.UrlEntry>(ExtendsParser.parse(extendsOf(entry), emptyMap()).single()).condition
        assertEquals("\${KARANDASH_TEST_UNSET_ENV} == 'production'", condition)
        val evaluate = { env: String? -> ConditionEvaluator(envProvider = { env }).evaluate(checkNotNull(condition)) }
        assertEquals(false, evaluate(null))
        assertEquals(true, evaluate("production"))
    }

    @Test
    fun `object form url naming a manifest set is a manifest reference with its condition`() {
        val entry = buildJsonObject {
            put("url", "@standard")
            put("if", "platform == 'gitlab'")
            put("merge-strategy", buildJsonObject { put("rules", "replace") })
        }
        val ref = assertIs<ExtendsEntry.ManifestRef>(ExtendsParser.parse(extendsOf(entry), emptyMap()).single())
        assertEquals("standard", ref.name)
        assertEquals("platform == 'gitlab'", ref.condition)
        assertEquals(mapOf("rules" to MergeMode.REPLACE), ref.mergeStrategy)
    }

    @Test
    fun `condition env lookup treats denylisted variables as unset`() {
        assertEquals(false, ConditionEvaluator().evaluate("\${HOME}"))
    }

    @Test
    fun `extends values interpolate against the env block of the config`() {
        val config = buildJsonObject {
            put("env", buildJsonObject { put("KD_EP_HOST", "https://example.com") })
            put(
                "extends",
                buildJsonArray {
                    add(JsonPrimitive("\${KD_EP_HOST}/a.yaml"))
                    add(
                        buildJsonObject {
                            put("url", "\${KD_EP_HOST}/b.yaml")
                            put(
                                "auth",
                                buildJsonObject {
                                    put("type", "bearer-token")
                                    put("token", "\${KD_EP_HOST}")
                                }
                            )
                        },
                    )
                },
            )
        }
        val entries = ConfigMapParser.parseBootstrapConfig(config).extends.map { assertIs<ExtendsEntry.UrlEntry>(it).source }

        assertEquals(listOf("https://example.com/a.yaml", "https://example.com/b.yaml"), entries.map { it.url })
        assertEquals(HttpAuth.Bearer("https://example.com"), entries[1].auth)
        assertEquals(emptyList(), entries[1].emptyAuthVars, "a variable with an env default is not empty")
    }

    @Test
    fun `merge strategy and file resolution are read in camelCase too`() {
        val entry = buildJsonObject {
            put("url", "https://example.com/base.yaml")
            put("mergeStrategy", buildJsonObject { put("rules", "append") })
            put("fileResolution", "strict")
        }
        val parsed = assertIs<ExtendsEntry.UrlEntry>(ExtendsParser.parse(extendsOf(entry), emptyMap()).single())

        assertEquals(mapOf("rules" to MergeMode.APPEND), parsed.mergeStrategy)
        assertEquals(RemoteFileResolution.STRICT, parsed.fileResolution)
    }

    @Test
    fun `invalid file-resolution and too many entries fail with config errors`() {
        val bad = buildJsonObject {
            put("url", "https://example.com/base.yaml")
            put("file-resolution", "sideways")
        }
        val error = assertFailsWith<ConfigException> { ExtendsParser.parse(extendsOf(bad), emptyMap()) }
        assertTrue(error.message.orEmpty().contains("sideways"))
        val many = buildJsonArray { repeat(3) { add(JsonPrimitive("https://example.com/$it.yaml")) } }
        assertFailsWith<ConfigException> { ExtendsParser.parse(extendsOf(many), emptyMap(), maxEntries = 2) }
    }

    @Test
    fun `non-string primitive extends is rejected`() {
        assertFailsWith<ConfigException> { ExtendsParser.parse(extendsOf(JsonPrimitive(42)), emptyMap()) }
    }

    @Test
    fun `a string merge-strategy sets the top-level merge and a bad one is a config error`() {
        fun entry(strategy: String) =
            buildJsonObject {
                put("url", "https://example.com/base.yaml")
                put("merge-strategy", strategy)
            }
        val parsed = assertIs<ExtendsEntry.UrlEntry>(ExtendsParser.parse(extendsOf(entry("shallow")), emptyMap()).single())
        assertEquals(MergeStrategy.SHALLOW, parsed.topLevelMerge)
        assertNull(parsed.mergeStrategy)
        val error = assertFailsWith<ConfigException> { ExtendsParser.parse(extendsOf(entry("sideways")), emptyMap()) }
        assertTrue(error.message.orEmpty().contains("sideways"), error.message)
    }

    @Test
    fun `a misspelled merge-strategy field mode is a config error naming the field`() {
        val entry = buildJsonObject {
            put("url", "https://example.com/base.yaml")
            put("merge-strategy", buildJsonObject { put("rules.labels.required", "apend") })
        }
        val error = assertFailsWith<ConfigException> { ExtendsParser.parse(extendsOf(entry), emptyMap()) }
        val message = error.message.orEmpty()
        assertTrue(message.contains("apend") && message.contains("rules.labels.required"), message)
    }
}
