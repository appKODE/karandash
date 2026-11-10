package io.karandash.plugins.ai.core.rules

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AiRuleAnalyzerUtils.effectivePrompt]: the JSON format
 * preamble + schema is always appended last regardless of prompt layering.
 */
class AiRuleAnalyzerUtilsEffectivePromptTest {
    private val basePrompt = "You are a code reviewer."
    private val schema = """{"comments": [{"file": "path", "line": 1}]}"""
    private val override = "Custom user prompt."
    private val extra = "Focus on security."

    @Test
    fun basePromptOnly_noSchema() {
        val result = AiRuleAnalyzerUtils.effectivePrompt(basePrompt)
        assertEquals(basePrompt, result)
    }

    @Test
    fun basePromptOnly_withSchema_includesPreambleAndSchema() {
        val result = AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt,
            responseFormatSuffix = schema,
        )
        assertContains(result, basePrompt)
        assertContains(result, "CRITICAL RESPONSE FORMAT REQUIREMENTS")
        assertContains(result, "Markdown fencing")
        assertContains(result, "Literal newline characters inside string")
        assertContains(result, "Required JSON format:")
        assertContains(result, schema)
    }

    @Test
    fun schemaAppearsAfterPreamble() {
        val result = AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt,
            responseFormatSuffix = schema,
        )
        val preambleIdx = result.indexOf("CRITICAL RESPONSE FORMAT REQUIREMENTS")
        val schemaIdx = result.indexOf(schema)
        assertTrue(schemaIdx > preambleIdx, "Schema must come after preamble")
    }

    @Test
    fun extraInstructions_withSchema() {
        val result = AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt,
            extraInstructions = extra,
            responseFormatSuffix = schema,
        )
        assertContains(result, basePrompt)
        assertContains(result, extra)
        assertContains(result, "CRITICAL RESPONSE FORMAT REQUIREMENTS")
        assertTrue(
            result.trimEnd().endsWith(schema),
            "Expected schema at end but got: ...${result.takeLast(100)}",
        )
    }

    @Test
    fun override_withSchema() {
        val result = AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt,
            systemPromptOverride = override,
            responseFormatSuffix = schema,
        )
        assertFalse(result.contains(basePrompt), "Base prompt should not appear when override is active")
        assertContains(result, override)
        assertContains(result, "CRITICAL RESPONSE FORMAT REQUIREMENTS")
        assertTrue(result.trimEnd().endsWith(schema))
    }

    @Test
    fun override_noSchema() {
        val result = AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt,
            systemPromptOverride = override,
        )
        assertEquals(override, result)
    }

    @Test
    fun extraInstructions_noSchema() {
        val result = AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt,
            extraInstructions = extra,
        )
        assertEquals("$basePrompt\n\nAdditional instructions:\n$extra", result)
    }

    @Test
    fun overrideTakesPrecedenceOverExtra() {
        val result = AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt,
            extraInstructions = extra,
            systemPromptOverride = override,
            responseFormatSuffix = schema,
        )
        assertFalse(result.contains(extra), "Extra instructions should be ignored when override is active")
        assertContains(result, override)
        assertContains(result, schema)
    }

    @Test
    fun schemaAppendedAfterExtraInstructions() {
        val result = AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt,
            extraInstructions = extra,
            responseFormatSuffix = schema,
        )
        val extraIdx = result.indexOf(extra)
        val schemaIdx = result.indexOf(schema)
        assertTrue(schemaIdx > extraIdx, "Schema ($schemaIdx) must come after extra instructions ($extraIdx)")
    }

    @Test
    fun `a system prompt over 10k chars reaches the provider whole`() {
        val largeBase = "A".repeat(12_000)
        val result = AiRuleAnalyzerUtils.effectivePrompt(largeBase, responseFormatSuffix = schema)
        assertContains(result, largeBase)
        assertTrue(result.trimEnd().endsWith(schema), "Schema must stay at the very end")
        assertFalse(result.contains("truncated"), "T0 is never cut")
    }

    @Test
    fun `a large override is never cut either`() {
        val largeOverride = "Line ${"X".repeat(200)}\n".repeat(60)
        val result = AiRuleAnalyzerUtils.effectivePrompt(basePrompt, systemPromptOverride = largeOverride, responseFormatSuffix = schema)
        assertContains(result, largeOverride)
        assertTrue(result.trimEnd().endsWith(schema))
    }

    @Test
    fun normalSizePrompt_notTruncated() {
        val normalBase = "You are a reviewer.\n".repeat(10)
        val result = AiRuleAnalyzerUtils.effectivePrompt(
            normalBase,
            responseFormatSuffix = schema,
        )
        assertContains(result, normalBase.trim())
        assertFalse(result.contains("truncated"), "Normal-size prompt should not be truncated")
    }
}
