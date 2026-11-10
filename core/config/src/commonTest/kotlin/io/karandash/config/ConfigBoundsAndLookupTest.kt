package io.karandash.config

import io.karandash.config.keys.AiReporterKeys
import io.karandash.config.schema.ConfigSections
import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigBoundsAndLookupTest {
    private fun limits(json: String): LimitsConfig = KarandashJson.ConfigDecoder.decodeFromString(LimitsConfig.serializer(), json)

    @Test
    fun `default limits and display raise no bounds warnings`() {
        assertEquals(emptyList(), LimitsConfig().boundsWarnings())
        assertEquals(emptyList(), DisplayConfig().boundsWarnings())
    }

    @Test
    fun `every out of bounds limit is clamped with one warning`() {
        val config = limits(
            """
            {"maxFindings": 0, "fetchTimeout": "0s",
             "expressions": {"maxLength": 0, "maxNestingDepth": 0},
             "extends": {"maxEntries": 0, "maxDepth": 0, "maxChainSize": 0},
             "glob": {"maxExpansion": 0, "maxDirectoryDepth": 0},
             "regex": {"maxPatternLength": 0, "maxQuantifierNesting": 0},
             "patterns": {"maxExpansionDepth": 0}}
            """.trimIndent(),
        )
        val warnings = config.boundsWarnings()
        assertEquals(12, warnings.size, warnings.joinToString("\n"))
        assertEquals("Config key 'limits.maxFindings' value 0 is out of bounds; using 100", warnings.first())
        assertEquals(100, config.effectiveMaxFindings)
        assertEquals(config.regex.effectiveMaxPatternLength, config.effectiveRegexMaxPatternLength())
        assertEquals(config.regex.effectiveMaxQuantifierNesting, config.effectiveRegexMaxQuantifierNesting())
        assertEquals(config.patterns.effectiveMaxExpansionDepth, config.effectivePatternMaxExpansionDepth())
        assertTrue(config.effectiveRegexMaxPatternLength() > 0)
    }

    @Test
    fun `http maxAttempts 50 is clamped with one warning and maxPages 0 reads every page silently`() {
        val config = KarandashJson.ConfigDecoder.decodeFromString(
            HttpConfig.serializer(),
            """{"retry": {"maxAttempts": 50}, "pagination": {"maxPages": 0}}""",
        )
        assertEquals(listOf("Config key 'http.retry.maxAttempts' value 50 is out of bounds; using 10"), config.boundsWarnings())
        assertEquals(emptyList(), HttpConfig().boundsWarnings())
    }

    @Test
    fun `display bounds clamp the sha length and debounce`() {
        val display =
            DisplayConfig(git = DisplayGitConfig(shortShaLength = 1), watcher = DisplayWatcherConfig(KarandashDuration.ofMinutes(5)))
        val warnings = display.boundsWarnings()
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].startsWith("Config key 'display.git.shortShaLength' value 1"))
        assertTrue(warnings[1].startsWith("Config key 'display.watcher.debounce'"))
    }

    @Test
    fun `http limits are coerced into their safe ranges`() {
        assertEquals(10, HttpRetryConfig(maxAttempts = 99).effectiveMaxAttempts)
        assertEquals(0, HttpRetryConfig(maxAttempts = -1).effectiveMaxAttempts)
        assertEquals(Int.MAX_VALUE, HttpPaginationConfig(maxPages = 0).effectiveMaxPages)
        assertEquals(1, HttpPaginationConfig(maxPages = 1).effectiveMaxPages)
        assertEquals(100, HttpPaginationConfig(maxPages = 1_000).effectiveMaxPages)
    }

    @Test
    fun `validate names every blank id`() {
        val blank = mapOf(" " to JsonNull)
        val config = Config(
            rules = RulesConfig(ruleConfigs = blank),
            platforms = blank,
            reporters = blank,
            aiReports = blank,
            aiMetrics = blank,
            enrichmentProviders = blank,
        )
        assertEquals(
            listOf(
                "Rule ID cannot be blank",
                "Platform ID cannot be blank",
                "Reporter format ID cannot be blank",
                "AI report ID cannot be blank",
                "AI metric ID cannot be blank",
                "Enrichment provider ID cannot be blank",
            ),
            config.validate(),
        )
        assertEquals(emptyList(), Config().validate())
    }

    @Test
    fun `enum fromString accepts any case and rejects unknown values`() {
        assertEquals(ScriptMode.AUTO, ScriptMode.fromString("AUTO"))
        assertEquals(DryRunScope.ALL, DryRunScope.fromString("All"))
        assertEquals(AuthType.BEARER, AuthType.fromString("Bearer"))
        assertTrue(assertFailsWith<IllegalArgumentException> { ScriptMode.fromString("x") }.message.orEmpty().contains("'x'"))
        assertTrue(assertFailsWith<IllegalArgumentException> { DryRunScope.fromString("x") }.message.orEmpty().contains("'all' or 'ai'"))
        assertFailsWith<IllegalArgumentException> { AuthType.fromString("x") }
    }

    @Test
    fun `report language is normalized to its base subtag`() {
        assertEquals("ru", ReportConfig.normalizeLanguage(" ru_RU "))
        assertEquals("pl", ReportConfig.normalizeLanguage("Polish"))
        assertEquals("de", ReportConfig.normalizeLanguage("de-AT"))
        assertEquals(ReportConfig.DEFAULT_LANGUAGE, ReportConfig.normalizeLanguage("  "))
    }

    @Test
    fun `rule config lookup tolerates naming conventions`() {
        val rules = RulesConfig(ruleConfigs = mapOf("goTitleLength" to JsonPrimitive(1)))
        assertEquals(listOf(JsonPrimitive(1)), rules.getRuleConfigElements("go-title-length"))
        assertEquals(emptyList(), rules.getRuleConfigElements("other"))
    }

    @Test
    fun `ai provider id is read from the ai block`() {
        assertEquals("openai", Config(ai = buildJsonObject { put("provider", "openai") }).aiProviderId())
        assertNull(Config(ai = JsonArray(emptyList())).aiProviderId())
        assertNull(Config().aiProviderId())
    }

    @Test
    fun `meta field names come from the serializer`() {
        assertEquals(RuleExecutionMeta.serializer().descriptor.elementsCount, RuleExecutionMeta.META_FIELD_NAMES.size)
    }

    @Test
    fun `known section and ai reporter key sets are complete`() {
        assertTrue(ConfigSections.RULES in ConfigSections.all && ConfigSections.PLUGINS in ConfigSections.all)
        assertEquals(26, ConfigSections.all.size)
        assertTrue(ConfigSections.SECURITY in ConfigSections.all && ConfigSections.BASELINE in ConfigSections.all)
        assertEquals(14, AiReporterKeys.ALL.size)
        assertTrue(AiReporterKeys.AI_PREDICTIONS in AiReporterKeys.ALL)
    }

    @Test
    fun `when config decodes string and object forms and rejects arrays`() {
        val serializer = WhenConfig.serializer()
        assertEquals(WhenConfig("a"), KarandashJson.Default.decodeFromString(serializer, "\"a\""))
        val full = KarandashJson.Default.decodeFromString(serializer, """{"expression":"b","override":{"required":true}}""")
        assertEquals("b", full.expression)
        assertEquals(
            full,
            KarandashJson.Default.decodeFromJsonElement(serializer, KarandashJson.Default.encodeToJsonElement(serializer, full))
        )
        assertFailsWith<IllegalStateException> { KarandashJson.Default.decodeFromString(serializer, "[1]") }
    }
}
