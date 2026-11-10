package io.karandash.plugins.ai.analyzers.changecategorization

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.ProcessRiskCalculator
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeCategorizationAnalyzerModuleTest {
    private val module = ChangeCategorizationAnalyzerModule()

    @Test
    fun `change-categorization is off by default`() {
        assertFalse(module.defaultEnabled, "No measurable precision, opt in with changeCategorization: true")
    }

    private val mr = createTestMergeRequest(
        title = "Fix crash on empty cart",
        labels = listOf("mobile"),
        commits = listOf(Commit("a1", "fix(cart): guard empty list\n\nbody", CommitAuthor("Dev", "dev@test.com"), "2026-01-01T00:00:00Z")),
    )

    private fun context(
        service: FakeAiService,
        budget: TokenBudget = TokenBudget(50_000),
    ) = AiAnalyzerContext(mr = mr, aiService = service, budget = budget, model = "m")

    @Test
    fun `suggested labels become one info finding`() =
        runTest {
            val service = FakeAiService("""{"suggestedLabels":["bug","mobile"],"confidence":0.9,"reasoning":"guard fix"}""")
            val findings = module.analyze(context(service))

            val finding = findings.single()
            assertEquals(Severity.INFO, finding.severity)
            assertEquals(ChangeCategorizationAnalyzerModule.ANALYZER_ID, finding.rule)
            assertEquals("bug, mobile", finding.metadata[AiFindingMetadataKeys.SUGGESTED_LABELS])
            assertEquals("true", finding.metadata[AiFindingMetadataKeys.IS_CATEGORIZATION])
        }

    @Test
    fun `prompt carries current labels and commit subjects only`() =
        runTest {
            val service = FakeAiService("""{"suggestedLabels":["bug"],"confidence":0.9,"reasoning":"r"}""")
            module.analyze(context(service))

            val prompt = service.requests.single().prompt
            assertTrue(prompt.contains("Current labels: mobile"), prompt)
            assertTrue(prompt.contains("- fix(cart): guard empty list"), prompt)
            assertTrue(!prompt.contains("body"), prompt)
        }

    @Test
    fun `empty label list yields no finding`() =
        runTest {
            val findings = module.analyze(context(FakeAiService("""{"suggestedLabels":[],"confidence":0.4,"reasoning":"unclear"}""")))
            assertTrue(findings.isEmpty())
        }

    @Test
    fun `malformed response yields a parse error warning`() =
        runTest {
            val finding = module.analyze(context(FakeAiService("not json"))).single()
            assertEquals(Severity.INFO, finding.severity)
        }

    @Test
    fun `prompt and finding carry the shared measured process risk`() =
        runTest {
            val service = FakeAiService("""{"suggestedLabels":["bug"],"confidence":0.9,"reasoning":"r"}""")
            val ctx = context(service)
            val findings = module.analyze(ctx)
            val finding = findings.first()
            val expected = ProcessRiskCalculator.calculate(ctx.mr).composite.formatDecimals(2)
            assertEquals(expected, finding.metadata[AiFindingMetadataKeys.PROCESS_RISK])
            val prompt = service.requests.single().prompt
            assertTrue(prompt.contains("## Measured Process Risk") && prompt.contains("Measured process risk: $expected"), prompt)
        }
}
