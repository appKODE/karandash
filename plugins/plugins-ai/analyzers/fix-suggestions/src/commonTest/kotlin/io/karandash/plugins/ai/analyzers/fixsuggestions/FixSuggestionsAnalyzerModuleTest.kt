package io.karandash.plugins.ai.analyzers.fixsuggestions

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.models.finding.CodeChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.finding.codeChange
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixSuggestionsAnalyzerModuleTest {
    private val module = FixSuggestionsAnalyzerModule()

    private val titleError = Finding(Severity.ERROR, "Title must follow conventional commits", "title-format")

    private fun context(
        service: FakeAiService,
        prior: List<Finding> = listOf(titleError),
    ) = AiAnalyzerContext(
        mr = createTestMergeRequest(title = "Update stuff"),
        aiService = service,
        budget = TokenBudget(50_000),
        model = "m",
        accumulatedFindings = prior,
    )

    @Test
    fun `fixes become info findings linked to the original rule`() =
        runTest {
            val service = FakeAiService(
                """{"fixes":[{"findingRule":"title-format","findingMessage":"Title must follow conventional commits",""" +
                    """"suggestedFix":"Rename to feat: update stuff","confidence":0.9}]}""",
            )
            val finding = module.analyze(context(service)).single()

            assertEquals(Severity.INFO, finding.severity)
            assertEquals("title-format", finding.metadata[AiFindingMetadataKeys.ORIGINAL_RULE])
            assertEquals("Rename to feat: update stuff", finding.metadata[AiFindingMetadataKeys.SUGGESTED_FIX])
            assertEquals("true", finding.metadata[AiFindingMetadataKeys.IS_FIX_SUGGESTION])
            assertTrue(
                service.requests
                    .single()
                    .prompt
                    .contains("Title must follow conventional commits")
            )
        }

    @Test
    fun `the fix goes to the suggestion and the message names the problem`() =
        runTest {
            val service = FakeAiService(
                """{"fixes":[{"findingRule":"title-format","findingMessage":"Title must follow conventional commits",""" +
                    """"suggestedFix":"Rename to feat: update stuff","confidence":0.9}]}""",
            )
            val finding = module.analyze(context(service)).single()

            assertEquals("[title-format] Title must follow conventional commits", finding.message)
            assertEquals("Rename to feat: update stuff", finding.suggestion)
        }

    @Test
    fun `info-only prior findings skip the AI call`() =
        runTest {
            val service = FakeAiService("{}")
            val prior = listOf(Finding(Severity.INFO, "fyi", "note"))

            assertTrue(module.analyze(context(service, prior)).isEmpty())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `malformed response yields a parse error warning`() =
        runTest {
            assertEquals(Severity.INFO, module.analyze(context(FakeAiService("nope"))).single().severity)
        }

    @Test
    fun `a fix with replacement code becomes a code change at its lines`() {
        val content =
            """{"fixes":[{"findingRule":"r","findingMessage":"m","suggestedFix":"f","confidence":0.8,""" +
                """"file":"src/A.kt","line":3,"endLine":4,"existingCode":"a\nb","suggestedCode":"c"}]}"""
        val change = module.parseResponse(content, "m", 1).single().codeChange()
        assertEquals(CodeChange("src/A.kt", 3, 4, "c", "a\nb"), change)
    }

    @Test
    fun `a fix without replacement code has no code change`() {
        val content = """{"fixes":[{"findingRule":"r","findingMessage":"m","suggestedFix":"f","confidence":0.8}]}"""
        val finding = module.parseResponse(content, "m", 1).single()
        assertEquals(null, finding.codeChange())
        assertEquals(null, finding.location)
    }
}
