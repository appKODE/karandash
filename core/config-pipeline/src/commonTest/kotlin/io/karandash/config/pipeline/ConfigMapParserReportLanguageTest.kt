package io.karandash.config.pipeline

import io.karandash.api.module.ConfigFormatModule
import io.karandash.config.Config
import io.karandash.config.ReportConfig
import io.karandash.manifest.ConfigSource
import io.karandash.normalize.deepNormalizeKeys
import io.karandash.sdk.config.anyToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `report.language`: canonical output language, `ai.response-language` alias, precedence and `extends:`. */
class ConfigMapParserReportLanguageTest {
    private fun parse(raw: Map<String, Any?>) = ConfigMapParser.parseFullConfig(anyToJsonElement(raw)).config

    private fun warnings(raw: Map<String, Any?>) = ReportLanguage.warnings(listOf(deepNormalizeKeys(anyToJsonElement(raw))))

    private fun aiLanguage(raw: Map<String, Any?>) = (parse(raw).ai as JsonObject)["responseLanguage"]?.jsonPrimitive?.content

    @Test
    fun `report language defaults to en when nothing is set`() {
        assertEquals(ReportConfig.DEFAULT_LANGUAGE, parse(mapOf("ai" to mapOf("model" to "m"))).report.language)
        assertEquals(emptyList(), warnings(mapOf("ai" to mapOf("model" to "m"))))
    }

    @Test
    fun `report language is read and a region tag maps to its base tag`() {
        val raw = mapOf("report" to mapOf("language" to "ru-RU"), "ai" to mapOf("model" to "m"))

        assertEquals("ru", parse(raw).report.language)
        assertEquals("ru", aiLanguage(raw))
        assertEquals(emptyList(), warnings(raw))
    }

    @Test
    fun `ai response-language alone is a silent alias of report language`() {
        val raw = mapOf("ai" to mapOf("response-language" to "Russian"))

        assertEquals("ru", parse(raw).report.language)
        assertEquals("Russian", aiLanguage(raw))
        assertEquals(emptyList(), warnings(raw))
    }

    @Test
    fun `report language wins over the alias and setting both is warned`() {
        val raw = mapOf("report" to mapOf("language" to "pl"), "ai" to mapOf("responseLanguage" to "German"))

        assertEquals("pl", parse(raw).report.language)
        assertEquals("pl", aiLanguage(raw))
        val warning = warnings(raw).single()
        assertTrue("report.language" in warning && "wins" in warning, warning)
    }

    @Test
    fun `a blank report language counts as unset`() {
        assertEquals(ReportConfig.DEFAULT_LANGUAGE, parse(mapOf("report" to mapOf("language" to ""))).report.language)

        val withAlias = mapOf("report" to mapOf("language" to " "), "ai" to mapOf("responseLanguage" to "Russian"))
        assertEquals("ru", parse(withAlias).report.language)
        assertEquals("Russian", aiLanguage(withAlias))
        assertEquals(emptyList(), warnings(withAlias))
    }

    @Test
    fun `report language of the parent is inherited through extends`() =
        runTest {
            val (config, _) = chain(mapOf("report" to mapOf("language" to "ru")), mapOf("ai" to mapOf("model" to "m")))

            assertEquals("ru", config.report.language)
            assertEquals("ru", (config.ai as JsonObject)["responseLanguage"]?.jsonPrimitive?.content)
        }

    @Test
    fun `a child report language beats a parent alias without a warning`() =
        runTest {
            val (config, warnings) = chain(
                mapOf("ai" to mapOf("response-language" to "German")),
                mapOf("report" to mapOf("language" to "pl")),
            )

            assertEquals("pl", config.report.language)
            assertEquals("pl", (config.ai as JsonObject)["responseLanguage"]?.jsonPrimitive?.content)
            assertEquals(emptyList(), warnings)
        }

    @Test
    fun `a child alias beats a parent report language without a warning`() =
        runTest {
            val (config, warnings) = chain(
                mapOf("report" to mapOf("language" to "ru"), "ai" to mapOf("model" to "m")),
                mapOf("ai" to mapOf("response-language" to "Polish")),
            )

            assertEquals("pl", config.report.language)
            assertEquals("Polish", (config.ai as JsonObject)["responseLanguage"]?.jsonPrimitive?.content)
            assertEquals(emptyList(), warnings)
        }

    @Test
    fun `a blank child report language keeps the parent value`() =
        runTest {
            val (config, _) = chain(
                mapOf("report" to mapOf("language" to "ru"), "ai" to mapOf("model" to "m")),
                mapOf("report" to mapOf("language" to "")),
            )

            assertEquals("ru", config.report.language)
        }

    @Test
    fun `a blank child alias keeps the parent alias`() =
        runTest {
            val (config, _) = chain(
                mapOf("ai" to mapOf("model" to "m", "response-language" to "Polish")),
                mapOf("ai" to mapOf("response-language" to "")),
            )

            assertEquals("pl", config.report.language)
        }

    /** The config of a parent and child `extends:` chain, and the language warnings of its layers. */
    private suspend fun chain(
        parent: Map<String, Any?>,
        child: Map<String, Any?>,
    ): Pair<Config, List<String>> {
        val module = object : ConfigFormatModule {
            override val formatId = "yaml"

            override suspend fun parseToJsonElement(content: String): JsonElement =
                anyToJsonElement(if (content == "parent") parent else child)
        }
        val chain = ConfigChain(
            listOf("parent", "child").mapIndexed { depth, name ->
                ChainEntry(
                    source = ConfigSource("file://$name.yaml"),
                    depth = depth,
                    mergeStrategy = null,
                    content = name,
                    format = "yaml"
                )
            },
        )
        val layers = mutableListOf<JsonElement>()
        val merged = ConfigChainParser(ConfigFormatRegistry(listOf(module)), onEntryParsed = { _, element -> layers += element })
            .parseAndMerge(chain)
        return ConfigMapParser.parseFullConfig(merged).config to ReportLanguage.warnings(layers)
    }
}
