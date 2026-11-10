package io.karandash.plugins.ai.analyzers.mrsummary

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.ProcessRiskCalculator
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MrSummaryExtraInstructionsTest {
    @Test
    fun `extra instructions reach the system prompt and the base prompt survives`() =
        runTest {
            val service = FakeAiService("""{"summary":"s"}""")
            MrSummaryAnalyzerModule().analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(),
                    aiService = service,
                    budget = TokenBudget(50_000),
                    model = "m",
                    extraInstructions = "Project rule: never log card numbers",
                ),
            )

            val system = service.requests
                .single()
                .systemPrompt
                .orEmpty()
            assertTrue(system.contains("Project rule: never log card numbers"), system)
            assertTrue(system.contains("reviewFocus"), system)
        }

    @Test
    fun `prompt summary risks and finding carry the shared measured process risk`() =
        runTest {
            val service = FakeAiService("""{"summary":"s","risks":["null cart"]}""")
            val ctx = AiAnalyzerContext(mr = createTestMergeRequest(), aiService = service, budget = TokenBudget(50_000), model = "m")
            val findings = MrSummaryAnalyzerModule().analyze(ctx)
            val finding = findings.first()
            val expected = ProcessRiskCalculator.calculate(ctx.mr).composite.formatDecimals(2)
            assertEquals(expected, finding.metadata[AiFindingMetadataKeys.PROCESS_RISK])
            val prompt = service.requests.single().prompt
            assertTrue(prompt.contains("## Measured Process Risk") && prompt.contains("Measured process risk: $expected"), prompt)
            assertTrue(finding.message.contains("Risks") && finding.message.contains("Measured process risk: $expected"), finding.message)
        }
}
