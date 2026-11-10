package io.karandash.plugins.ai.analyzers.designdocdrift

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.context.DesignDocEnricher
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignDocDriftGateTest {
    private val module = DesignDocDriftAnalyzerModule()
    private val designDoc = AiContextBlock(heading = "", content = "Use Redis.", priority = 1, source = DesignDocEnricher.MODULE_ID)

    private fun context(
        service: FakeAiService,
        extraInstructions: String?,
        blocks: List<AiContextBlock>,
    ) = AiAnalyzerContext(
        mr = createTestMergeRequest(),
        aiService = service,
        budget = TokenBudget(50_000),
        model = "m",
        extraInstructions = extraInstructions,
        contextBlocks = blocks,
    )

    @Test
    fun `extra instructions without a design doc block skip the AI call`() =
        runTest {
            val service = FakeAiService("""{"hasDrift":false}""")
            module.analyze(context(service, extraInstructions = "Respond in English.", blocks = emptyList()))
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `a design doc block runs the analyzer without extra instructions`() =
        runTest {
            val service = FakeAiService("""{"hasDrift":false}""")
            module.analyze(context(service, extraInstructions = null, blocks = listOf(designDoc)))
            assertEquals(1, service.requests.size)
        }

    @Test
    fun `extra instructions reach the system prompt and the base prompt survives`() =
        runTest {
            val service = FakeAiService("""{"hasDrift":false}""")
            module.analyze(context(service, extraInstructions = "Project rule: never log card numbers", blocks = listOf(designDoc)))
            val system = service.requests
                .single()
                .systemPrompt
                .orEmpty()
            assertTrue(system.contains("Project rule: never log card numbers"), system)
            assertTrue(system.contains("Required JSON format"), system)
        }
}
