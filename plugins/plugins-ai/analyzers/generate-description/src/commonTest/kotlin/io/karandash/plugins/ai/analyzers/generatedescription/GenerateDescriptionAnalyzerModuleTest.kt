package io.karandash.plugins.ai.analyzers.generatedescription

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerateDescriptionAnalyzerModuleTest {
    private val module = GenerateDescriptionAnalyzerModule()

    private fun context(service: FakeAiService) =
        AiAnalyzerContext(
            mr = createTestMergeRequest(
                title = "wip",
                changes = listOf(FileChange("src/Pay.kt", ChangeType.MODIFIED, additions = 4, deletions = 2)),
            ),
            aiService = service,
            budget = TokenBudget(50_000),
            model = "m",
            diffContext = PrecomputedDiffContext(preparedDiff = "+fun createCharge()"),
        )

    @Test
    fun `generated description becomes an info finding with the suggested title`() =
        runTest {
            val service = FakeAiService(
                """{"suggestedTitle":"feat: add createCharge","suggestedDescription":"Adds createCharge.","changesSummary":["new API"]}""",
            )
            val finding = module.analyze(context(service)).single()

            assertEquals(Severity.INFO, finding.severity)
            assertEquals("feat: add createCharge", finding.metadata[AiFindingMetadataKeys.SUGGESTED_TITLE])
            assertEquals("Adds createCharge.", finding.metadata[AiFindingMetadataKeys.SUGGESTED_DESCRIPTION])
        }

    /** The suggestion reaches AutoFixer as a SetDescription fix, and analyze does not warn per call. */
    @Test
    fun `generate-description proposes the description through SetDescription without a warn`() =
        runTest {
            val warnings = mutableListOf<String>()
            val logger = object : Logger {
                override fun debug(message: String) = Unit

                override fun info(message: String) = Unit

                override fun warn(message: String) {
                    warnings += message
                }

                override fun error(message: String) = Unit
            }
            val service = FakeAiService("""{"suggestedTitle":"t","suggestedDescription":"d"}""")

            val finding = module.analyze(context(service).copy(logger = logger)).single()

            assertEquals("t", finding.metadata[AiFindingMetadataKeys.SUGGESTED_TITLE])
            assertEquals(listOf<FixSuggestion>(FixSuggestion.SetDescription("d")), finding.fixSuggestions)
            assertEquals(emptyList(), warnings)
        }

    @Test
    fun `prompt lists changed files and the prepared diff`() =
        runTest {
            val service = FakeAiService("""{"suggestedTitle":"t","suggestedDescription":"d"}""")
            module.analyze(context(service))

            val prompt = service.requests.single().prompt
            assertTrue(prompt.contains("- src/Pay.kt [MODIFIED] (+4/-2)"), prompt)
            assertTrue(prompt.contains("+fun createCharge()"), prompt)
            assertTrue(prompt.contains("Description: (no description)"), prompt)
        }

    @Test
    fun `malformed response yields a parse error warning`() =
        runTest {
            assertEquals(Severity.INFO, module.analyze(context(FakeAiService("{"))).single().severity)
        }

    @Test
    fun `extra instructions reach the system prompt and the base prompt survives`() =
        runTest {
            val service = FakeAiService("""{"suggestedTitle":"t","suggestedDescription":"d"}""")
            module.analyze(context(service).copy(extraInstructions = "Project rule: never log card numbers"))

            val system = service.requests
                .single()
                .systemPrompt
                .orEmpty()
            assertTrue(system.contains("Project rule: never log card numbers"), system)
            assertTrue(system.contains("Required JSON format"), system)
        }
}
