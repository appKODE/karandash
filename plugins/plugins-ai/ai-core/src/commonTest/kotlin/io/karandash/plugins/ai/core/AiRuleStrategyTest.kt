package io.karandash.plugins.ai.core

import io.karandash.api.support.mapToJsonElement
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

class AiRuleStrategyTest {
    @Test
    fun `fromString parses lowercase`() {
        assertEquals(AiRuleStrategy.ALWAYS, AiRuleStrategy.fromString("always"))
        assertEquals(AiRuleStrategy.FALLBACK, AiRuleStrategy.fromString("fallback"))
        assertEquals(AiRuleStrategy.SUPPLEMENT, AiRuleStrategy.fromString("supplement"))
    }

    @Test
    fun `fromString parses uppercase`() {
        assertEquals(AiRuleStrategy.FALLBACK, AiRuleStrategy.fromString("FALLBACK"))
    }

    @Test
    fun `fromString parses kebab-case`() {
        assertEquals(AiRuleStrategy.ALWAYS, AiRuleStrategy.fromString("always"))
    }

    @Test
    fun `fromString returns ALWAYS for unknown`() {
        assertEquals(AiRuleStrategy.ALWAYS, AiRuleStrategy.fromString("unknown"))
        assertEquals(AiRuleStrategy.ALWAYS, AiRuleStrategy.fromString(""))
    }

    @Test
    fun `AiRuleStrategyConfig deserialization parses all fields`() {
        val map = mapOf(
            "strategy" to "fallback",
            "covers" to listOf("title-format", "commit-messages"),
            "minSeverity" to "warning",
        )
        val config = KarandashJson.Default.decodeFromJsonElement<AiRuleStrategyConfig>(mapToJsonElement(map))

        assertEquals(AiRuleStrategy.FALLBACK, config.strategy)
        assertEquals(setOf("title-format", "commit-messages"), config.covers)
        assertEquals("warning", config.minSeverity)
    }

    @Test
    fun `AiRuleStrategyConfig deserialization uses defaults`() {
        val config = KarandashJson.Default.decodeFromJsonElement<AiRuleStrategyConfig>(mapToJsonElement(emptyMap()))

        assertEquals(AiRuleStrategy.ALWAYS, config.strategy)
        assertEquals(emptySet(), config.covers)
        assertEquals("info", config.minSeverity)
    }

    @Test
    fun `AiRuleStrategyConfig deserialization handles camelCase minSeverity`() {
        val map = mapOf("minSeverity" to "error")
        val config = KarandashJson.Default.decodeFromJsonElement<AiRuleStrategyConfig>(mapToJsonElement(map))
        assertEquals("error", config.minSeverity)
    }
}
