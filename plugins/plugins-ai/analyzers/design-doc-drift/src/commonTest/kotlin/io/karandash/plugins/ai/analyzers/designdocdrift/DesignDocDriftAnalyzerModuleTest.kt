package io.karandash.plugins.ai.analyzers.designdocdrift

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesignDocDriftAnalyzerModuleTest {
    private val module = DesignDocDriftAnalyzerModule()
    private val mr = createTestMergeRequest(
        title = "Implement caching layer",
        description = "Adds Redis caching as designed in the architecture doc.",
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

    // -- module metadata --

    @Test
    fun `defaultEnabled is false`() {
        assertFalse(module.defaultEnabled)
    }

    @Test
    fun `supportedEnrichment includes jira and confluence`() {
        assertTrue("jira" in module.supportedEnrichment)
        assertTrue("confluence" in module.supportedEnrichment)
    }

    // -- buildPrompt --

    @Test
    fun `buildPrompt leaves the design doc to the shared context blocks`() {
        val prompt = module.buildPrompt(testContext(extraInstructions = "Respond in English."))
        assertFalse(prompt.contains("## Design Documentation"))
        assertFalse(prompt.contains("Respond in English."))
        assertTrue(prompt.contains("## Merge Request"))
        assertTrue(prompt.contains("## Diff"))
    }

    // -- parseResponse --

    @Test
    fun `parseResponse returns empty list when hasDrift false`() {
        val json = """{"hasDrift":false,"driftScore":0.1,"divergedSections":[],""" +
            """"recommendations":[],"summary":"Compliant"}"""
        val findings = module.parseResponse(json, "test-model", 100)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `parseResponse routes drift finding into its own presentation section`() {
        val json = """{"hasDrift":true,"driftScore":0.3,"divergedSections":[],""" +
            """"recommendations":[],"summary":"Minor drift"}"""
        val finding = module.parseResponse(json, "test-model", 10).single()
        assertEquals("Design doc drift", finding.metadata[io.karandash.models.finding.PresentationKeys.SECTION])
    }

    @Test
    fun `parseResponse returns WARNING for high drift score`() {
        val json = """{"hasDrift":true,"driftScore":0.8,"divergedSections":["Caching strategy"],""" +
            """"recommendations":["Use Redis instead of Memcached"],"summary":"Major drift"}"""
        val findings = module.parseResponse(json, "test-model", 200)
        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings.first().severity)
        assertTrue(findings.first().message.contains("80%"))
    }

    @Test
    fun `parseResponse returns INFO for low drift score`() {
        val json = """{"hasDrift":true,"driftScore":0.4,"divergedSections":["Naming"],""" +
            """"recommendations":["Rename class"],"summary":"Minor drift"}"""
        val findings = module.parseResponse(json, "test-model", 150)
        assertEquals(1, findings.size)
        assertEquals(Severity.INFO, findings.first().severity)
    }

    @Test
    fun `parseResponse includes diverged sections in explanation`() {
        val json = """{"hasDrift":true,"driftScore":0.7,"divergedSections":["Auth flow","Rate limiting"],""" +
            """"recommendations":["Follow design"],"summary":"Drift found"}"""
        val findings = module.parseResponse(json, "test-model", 200)
        val explanation = findings.first().explanation ?: ""
        assertTrue(explanation.contains("Auth flow"))
        assertTrue(explanation.contains("Rate limiting"))
    }

    @Test
    fun `recommendations become the suggestion not the explanation`() {
        val json = """{"hasDrift":true,"driftScore":0.7,"divergedSections":["Auth flow"],""" +
            """"recommendations":["Route createCharge through ApiClient","Update the design doc"],"summary":"Drift found"}"""
        val finding = module.parseResponse(json, "test-model", 200).single()

        assertEquals("Route createCharge through ApiClient; Update the design doc", finding.suggestion)
        assertFalse(finding.explanation.orEmpty().contains("Recommendations"))
        assertFalse(finding.message.contains("ApiClient"))
    }

    @Test
    fun `parseResponse falls back to error finding on malformed json`() {
        val findings = module.parseResponse("not-json", "test-model", 0)
        assertNotNull(findings.firstOrNull())
    }

    @Test
    fun `drift percentage is rounded not truncated by float error`() {
        // 0.29 * 100 == 28.999999999999996 in IEEE 754, so truncation printed 28%
        val json = """{"hasDrift":true,"driftScore":0.29,"divergedSections":["Cache"],""" +
            """"recommendations":["Sync doc"],"summary":"Minor drift"}"""
        val message = module.parseResponse(json, "m", 1).single().message
        assertTrue("29%" in message, message)
    }
}
