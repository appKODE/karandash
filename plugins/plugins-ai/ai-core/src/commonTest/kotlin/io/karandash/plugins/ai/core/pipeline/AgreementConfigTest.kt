package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.api.support.mapToJsonElement
import io.karandash.plugins.ai.core.AiPluginConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ai.review-comments.samples`, `ai.agreement`, `ai.hints` and the `ai.context` flags: defaults and bad values. */
class AgreementConfigTest {
    private fun parse(map: Map<String, Any?>) = AiPluginConfig.fromPluginConfig(mapToJsonElement(map))

    private fun lifted(map: Map<String, Any?>) =
        AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(mapToJsonElement(map)))

    @Test
    fun `defaults are two samples at 0_5 window 3 similarity 0_3 demote and hints of 400 tokens`() {
        val cfg = parse(emptyMap())

        assertEquals(ReviewSamplingConfig(samples = 2, sampleTemperature = 0.5), cfg.reviewSampling)
        assertEquals(AgreementConfig(lineWindow = 3, minSimilarity = 0.3, singleton = AgreementConfig.DEMOTE), cfg.agreement)
        assertEquals(HintsConfig(enabled = true, maxTokens = 400), cfg.hints)
        assertTrue(cfg.contextStripImports)
        assertTrue(cfg.contextImportGraph)
    }

    @Test
    fun `review-comments samples and sampleTemperature are lifted from the review-comments block`() {
        val cfg = lifted(mapOf("reviewComments" to mapOf("enabled" to true, "samples" to 1, "sampleTemperature" to 0.9)))

        assertEquals(ReviewSamplingConfig(samples = 1, sampleTemperature = 0.9), cfg.reviewSampling)
    }

    @Test
    fun `agreement and hints keys are read`() {
        val cfg = parse(
            mapOf(
                "agreement" to mapOf("lineWindow" to 5, "minSimilarity" to 0.5, "singleton" to "drop"),
                "hints" to mapOf("enabled" to false, "maxTokens" to 100),
                "context" to mapOf("stripImports" to false, "importGraph" to "false"),
            ),
        )

        assertEquals(AgreementConfig(5, 0.5, AgreementConfig.DROP), cfg.agreement)
        assertEquals(HintsConfig(false, 100), cfg.hints)
        assertFalse(cfg.contextStripImports)
        assertFalse(cfg.contextImportGraph)
    }

    @Test
    fun `a bad value throws ConfigParseException naming its key`() {
        val cases = mapOf(
            "ai.review-comments.samples" to mapOf("reviewSampling" to mapOf("samples" to 4)),
            "ai.review-comments.sample-temperature" to mapOf("reviewSampling" to mapOf("sampleTemperature" to 2.5)),
            "ai.agreement.lineWindow" to mapOf("agreement" to mapOf("lineWindow" to -1)),
            "ai.agreement.minSimilarity" to mapOf("agreement" to mapOf("minSimilarity" to 1.5)),
            "ai.agreement.singleton" to mapOf("agreement" to mapOf("singleton" to "maybe")),
            "ai.hints.maxTokens" to mapOf("hints" to mapOf("maxTokens" to 0)),
            "ai.context.stripImports" to mapOf("context" to mapOf("stripImports" to "sometimes")),
            "ai.context.importGraph" to mapOf("context" to mapOf("importGraph" to 3)),
        )
        cases.forEach { (key, map) ->
            val e = assertFailsWith<ConfigParseException>(key) { parse(map) }
            assertTrue(e.message.orEmpty().startsWith(key), "${e.message} names $key")
        }
    }

    @Test
    fun `samples 0 from the review-comments block is rejected`() {
        val e = assertFailsWith<ConfigParseException> { lifted(mapOf("reviewComments" to mapOf("samples" to 0))) }
        assertTrue(e.message.orEmpty().startsWith("ai.review-comments.samples"))
    }

    @Test
    fun `context flags do not leak into the context template variables`() {
        val cfg = parse(mapOf("context" to mapOf("stripImports" to true, "team" to "core")))

        assertEquals(setOf("team"), cfg.context.keys)
    }
}
