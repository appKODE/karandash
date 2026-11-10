package io.karandash.plugins.ai.analyzers.descriptionquality

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiTokenBudget
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescriptionQualityAnalyzeTest {
    private val module = DescriptionQualityAnalyzerModule()

    private class RecordingBudget : AiTokenBudget {
        var spent = 0

        override fun canSpend(estimatedTokens: Int) = true

        override fun recordSpend(tokens: Int) {
            spent += tokens
        }

        override fun remaining() = Int.MAX_VALUE

        override fun isExhausted() = false
    }

    private fun context(
        description: String?,
        ai: FakeAiService,
        budget: AiTokenBudget = RecordingBudget(),
        config: JsonObject = JsonObject(emptyMap()),
    ) = AiAnalyzerContext(
        mr = createTestMergeRequest(title = "Fix login", description = description),
        aiService = ai,
        budget = budget,
        model = "m1",
        config = config,
    )

    @Test
    fun `blank description yields a zero score warning without an AI call`() =
        runTest {
            val ai = FakeAiService("unused")
            val findings = module.analyze(context("   ", ai))
            assertEquals(0, ai.requests.size)
            val finding = findings.single()
            assertEquals(DescriptionQualityAnalyzerModule.ANALYZER_ID, finding.rule)
            assertEquals("0.0", finding.metadata[AiFindingMetadataKeys.QUALITY_SCORE])
            assertEquals("0", finding.metadata[AiFindingMetadataKeys.AI_TOKENS_USED])
            assertEquals("MR description is empty.", finding.message)
            assertEquals("Add a description explaining what changed and why.", finding.suggestion)
        }

    @Test
    fun `analyze sends the description and records metadata but not spend`() =
        runTest {
            val ai = FakeAiService(
                """{"qualityScore":0.4,"issues":[{"category":"why_changed","suggestion":"Explain why"}],"summary":"Thin"}""",
                totalTokens = 300,
            )
            val budget = RecordingBudget()
            val finding = module.analyze(context("Fixes the login.", ai, budget)).single()
            val request = ai.requests.single()
            assertTrue("Fixes the login." in request.prompt)
            assertEquals("m1", request.model)
            assertTrue(request.systemPrompt.orEmpty().contains("qualityScore"))
            assertEquals(0, budget.spent)
            assertTrue("score: 0.40" in finding.message, finding.message)
            assertEquals("why_changed: Explain why", finding.suggestion)
            assertEquals("1", finding.metadata[AiFindingMetadataKeys.ISSUE_COUNT])
        }

    @Test
    fun `limits descriptionMaxTokens caps the response tokens`() =
        runTest {
            val ai = FakeAiService("""{"qualityScore":0.9,"summary":"ok"}""")
            val config = buildJsonObject { putJsonObject("limits") { put("descriptionMaxTokens", 77) } }
            val findings = module.analyze(context("Good description.", ai, config = config))
            assertTrue(findings.isEmpty())
            assertEquals(77, ai.requests.single().maxTokens)
        }

    @Test
    fun `extra instructions are sent once`() =
        runTest {
            val ai = FakeAiService("""{"qualityScore":0.9,"summary":"ok"}""")
            module.analyze(context("Some text.", ai).copy(extraInstructions = "TICKET PROJ-1"))
            val request = ai.requests.single()
            val sent = (request.systemPrompt.orEmpty() + request.prompt).split("TICKET PROJ-1").size - 1
            assertEquals(1, sent, request.toString())
        }

    @Test
    fun `resolved prompt replaces the default system prompt`() =
        runTest {
            val ai = FakeAiService("""{"qualityScore":0.9,"summary":"ok"}""")
            module.analyze(context("Some text.", ai).copy(resolvedPrompt = "CUSTOM SYSTEM"))
            val system = ai.requests
                .single()
                .systemPrompt
                .orEmpty()
            assertTrue(system.startsWith("CUSTOM SYSTEM"), system)
            assertTrue("Evaluate the MR description" !in system, system)
        }
}
