package io.karandash.plugins.ai.analyzers.descriptionquality

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DescriptionQualityAnalyzerModuleTest {
    private val module = DescriptionQualityAnalyzerModule()

    @Test
    fun `description-quality is off by default`() {
        assertFalse(module.defaultEnabled, "No measurable precision, opt in with descriptionQuality: true")
    }

    private val mr = createTestMergeRequest(
        title = "Refactor auth module",
        description = "Refactors the SSO login flow to fix token rotation issues.",
    )

    private val stubAiService = object : AiService {
        override val isAvailable = false

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?
        ): AiCompletionResponse = error("Not expected in unit tests")
    }

    private val unlimitedBudget = object : AiTokenBudget {
        override fun canSpend(estimatedTokens: Int) = true

        override fun recordSpend(tokens: Int) = Unit

        override fun remaining() = Int.MAX_VALUE

        override fun isExhausted() = false
    }

    private fun testContext(extraInstructions: String? = null): AiAnalyzerContext =
        AiAnalyzerContext(
            mr = mr,
            aiService = stubAiService,
            budget = unlimitedBudget,
            model = "test",
            extraInstructions = extraInstructions,
        )

    // -- supportedEnrichment --

    @Test
    fun `supportedEnrichment includes jira prefix`() {
        assertTrue("jira" in module.supportedEnrichment)
    }

    // -- buildPrompt --

    @Test
    fun `buildPrompt includes MR description`() {
        val prompt = module.buildPrompt(testContext())
        assertTrue(prompt.contains("Refactors the SSO login flow"))
        assertTrue(prompt.contains("## Merge Request"))
        assertTrue(prompt.contains("## Description"))
    }

    @Test
    fun `buildPrompt includes Knowledge Context when extraInstructions present`() {
        val extra = "#### Linked tickets\n" +
            "<untrusted-content source=\"ticket-content-enricher\">\n" +
            "**PROJ-1**: Add AC coverage\n</untrusted-content>"
        val prompt = module.buildPrompt(testContext(extraInstructions = extra))
        assertTrue(prompt.contains("## Knowledge Context"))
        assertTrue(prompt.contains("PROJ-1"))
        assertTrue(prompt.contains("<untrusted-content"))
    }

    @Test
    fun `buildPrompt omits Knowledge Context when extraInstructions null`() {
        val prompt = module.buildPrompt(testContext(extraInstructions = null))
        assertFalse(prompt.contains("## Knowledge Context"))
    }

    @Test
    fun `buildPrompt omits Knowledge Context when extraInstructions blank`() {
        val prompt = module.buildPrompt(testContext(extraInstructions = "   "))
        assertFalse(prompt.contains("## Knowledge Context"))
    }

    // -- parseDescriptionResponse --

    @Test
    fun `parseDescriptionResponse returns empty list for high score with no issues`() {
        val json = """{"qualityScore":0.9,"issues":[],"summary":"Excellent description"}"""
        val findings = module.parseDescriptionResponse(json, 100, "test-model")
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `parseDescriptionResponse returns warning for issues`() {
        val json = """{"qualityScore":0.4,"issues":[""" +
            """{"category":"test_plan","suggestion":"Add test plan"}],"summary":"Missing test info"}"""
        val findings = module.parseDescriptionResponse(json, 50, "test-model")
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("0.40"))
    }

    @Test
    fun `parseDescriptionResponse falls back to error finding on malformed json`() {
        val findings = module.parseDescriptionResponse("not-json", 0, "test-model")
        assertNotNull(findings.firstOrNull())
    }

    @Test
    fun `score is rounded to two decimals not truncated by float error`() {
        // 0.29 * 100 == 28.999999999999996 in IEEE 754, so truncation printed 0.28
        val json = """{"qualityScore":0.29,"issues":[{"category":"clarity","suggestion":"Reword"}],"summary":"Unclear"}"""
        val message = module.parseDescriptionResponse(json, 10, "m").single().message
        assertTrue("score: 0.29" in message, message)
    }
}
