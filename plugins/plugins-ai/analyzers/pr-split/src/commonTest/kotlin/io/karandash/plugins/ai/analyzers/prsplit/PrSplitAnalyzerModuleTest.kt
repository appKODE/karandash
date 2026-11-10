package io.karandash.plugins.ai.analyzers.prsplit

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.ProcessRiskCalculator
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrSplitAnalyzerModuleTest {
    private val module = PrSplitAnalyzerModule()

    private fun context(
        service: FakeAiService,
        fileCount: Int = 4,
    ) = AiAnalyzerContext(
        mr = createTestMergeRequest(changes = (1..fileCount).map { FileChange("src/F$it.kt", ChangeType.MODIFIED, additions = 3) }),
        aiService = service,
        budget = TokenBudget(50_000),
        model = "m",
        config = JsonObject(mapOf("splitThreshold" to JsonPrimitive(3))),
    )

    @Test
    fun `split recommendation reports the split count`() =
        runTest {
            val service = FakeAiService(
                """{"shouldSplit":true,"confidence":0.8,"suggestedSplits":[{"name":"a","rationale":"r","files":["src/F1.kt"]},""" +
                    """{"name":"b","rationale":"r","files":["src/F2.kt"]}],"summary":"two concerns"}""",
            )
            val finding = module.analyze(context(service)).single()

            assertEquals(Severity.INFO, finding.severity)
            assertEquals("true", finding.metadata[AiFindingMetadataKeys.SHOULD_SPLIT])
            assertEquals("2", finding.metadata[AiFindingMetadataKeys.SPLIT_COUNT])
        }

    @Test
    fun `no split recommendation produces no finding`() =
        runTest {
            val findings = module.analyze(context(FakeAiService("""{"shouldSplit":false,"confidence":0.7,"summary":"cohesive"}""")))
            assertTrue(findings.isEmpty(), "$findings")
        }

    @Test
    fun `MR at the threshold skips the AI call`() =
        runTest {
            val service = FakeAiService("{}")
            assertTrue(module.analyze(context(service, fileCount = 3)).isEmpty())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `extra instructions reach the system prompt and the base prompt survives`() =
        runTest {
            val service = FakeAiService("""{"shouldSplit":false,"confidence":0.7,"summary":"cohesive"}""")
            module.analyze(context(service).copy(extraInstructions = "Project rule: never log card numbers"))

            val system = service.requests
                .single()
                .systemPrompt
                .orEmpty()
            assertTrue(system.contains("Project rule: never log card numbers"), system)
            assertTrue(system.contains("Required JSON format"), system)
        }

    @Test
    fun `prompt and finding carry the shared measured process risk`() =
        runTest {
            val service = FakeAiService(
                """{"shouldSplit":true,"confidence":0.8,"summary":"s",""" +
                    """"suggestedSplits":[{"name":"a","rationale":"r","files":["src/F1.kt"]}]}""",
            )
            val ctx = context(service)
            val findings = module.analyze(ctx)
            val finding = findings.first()
            val expected = ProcessRiskCalculator.calculate(ctx.mr).composite.formatDecimals(2)
            assertEquals(expected, finding.metadata[AiFindingMetadataKeys.PROCESS_RISK])
            val prompt = service.requests.single().prompt
            assertTrue(prompt.contains("## Measured Process Risk") && prompt.contains("Measured process risk: $expected"), prompt)
        }

    @Test
    fun `module descriptor exposes identity and feature key`() =
        runTest {
            assertEquals("ai-pr-split-analyzer", module.id)
            assertEquals("AI PR Split", module.name)
            assertEquals("1.0.0", module.version)
            assertEquals("prSplit", module.featureKey)
            assertTrue(module.mrLevel)
            assertFalse(module.defaultEnabled)
            assertTrue(module.explanation.contains("split"), module.explanation)
            assertTrue(module.initialize(TestPluginContext()))
        }

    @Test
    fun `unparseable model output becomes a parse error finding`() {
        val finding = module.parseResponse("not json at all", "m", 7).single()
        assertEquals(module.analyzerId, finding.rule)
        assertTrue(finding.message.contains("AI PR split analysis"), finding.message)
    }

    @Test
    fun `buildPrompt lists the first line of each commit with the default risk`() {
        val mr = createTestMergeRequest(
            commits = listOf(
                Commit(
                    "abc",
                    "First line\nbody",
                    CommitAuthor("A", "a@example.com"),
                    "2024-01-01T00:00:00Z",
                ),
            ),
        )
        val ctx = AiAnalyzerContext(mr, FakeAiService("{}"), TokenBudget(1000), "m")
        val prompt = module.buildPrompt(ctx)
        assertTrue("## Commits (1 total)" in prompt, prompt)
        assertTrue("- First line" in prompt && "body" !in prompt, prompt)
    }
}
