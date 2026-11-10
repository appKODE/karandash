package io.karandash.plugins.ai.core

import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.config.ChunkingConfig
import io.karandash.plugins.ai.core.router.RoutingStrategy
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AiContextRoutingConfigTest {
    private fun parse(json: String): AiPluginConfig {
        val element = KarandashJson.Default.parseToJsonElement(json)
        return AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(element))
    }

    private fun parseRaw(json: String): JsonElement = KarandashJson.Default.parseToJsonElement(json)

    @Test
    fun `absent blocks stay null`() {
        val cfg = parse("{}")
        assertNull(cfg.chunking)
        assertNull(cfg.incremental)
        assertNull(cfg.crossFileContext)
        assertNull(cfg.processRisk)
        assertNull(cfg.routing)
    }

    @Test
    fun `boolean shorthand enables or disables a block`() {
        val cfg = parse("""{"chunking": true, "incremental": false, "processRisk": true}""")
        assertEquals(ChunkingConfig(), cfg.chunking)
        assertEquals(false, cfg.incremental?.enabled)
        assertEquals(true, cfg.processRisk?.enabled)
    }

    @Test
    fun `chunking sizes are clamped`() {
        val cfg = parse("""{"chunking": {"chunkSize": 100, "overlapSize": 500, "maxChunks": 0}}""")
        assertEquals(ChunkingConfig(chunkSize = 100, overlapSize = 99, maxChunks = 1), cfg.chunking)
    }

    @Test
    fun `crossFileContext processRisk and routing blocks parse`() {
        val cfg = AiPluginConfig.fromPluginConfig(
            parseRaw(
                """
                {
                  "crossFileContext": {"maxContextFiles": 2, "captureSignatures": false},
                  "processRisk": {"factors": {"authorFamiliarity": false}},
                  "routing": {
                    "strategy": "quality-first",
                    "riskThreshold": 0.7,
                    "tiers": [{"name": "strong", "models": ["big"]}, {"name": "cheap", "models": ["small"]}]
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(2, cfg.crossFileContext?.maxContextFiles)
        assertFalse(cfg.crossFileContext?.captureSignatures ?: true)
        assertEquals(false, cfg.processRisk?.factors?.authorFamiliarity)
        assertEquals(RoutingStrategy.QUALITY_FIRST, cfg.routing?.strategy)
        assertEquals(0.7, cfg.routing?.riskThreshold)
        assertEquals(listOf("strong", "cheap"), cfg.routing?.tiers?.map { it.name })
    }
}
