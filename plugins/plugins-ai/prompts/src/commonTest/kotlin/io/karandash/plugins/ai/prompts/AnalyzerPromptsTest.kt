package io.karandash.plugins.ai.prompts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every analyzer has its own non-blank built-in prompt in [AnalyzerPrompts]. */
class AnalyzerPromptsTest {
    @Test
    fun `every analyzer prompt is present and distinct`() {
        val prompts = mapOf(
            "change-categorization" to AnalyzerPrompts.CHANGE_CATEGORIZATION,
            "code-quality" to AnalyzerPrompts.CODE_QUALITY,
            "code-suggestions" to AnalyzerPrompts.CODE_SUGGESTIONS,
            "review-thread-analysis" to AnalyzerPrompts.COMMENT_ANALYSIS,
            "description-quality" to AnalyzerPrompts.DESCRIPTION_QUALITY,
            "design-doc-drift" to AnalyzerPrompts.DESIGN_DOC_DRIFT,
            "fix-suggestions" to AnalyzerPrompts.FIX_SUGGESTIONS,
            "generate-description" to AnalyzerPrompts.GENERATE_DESCRIPTION,
            "mr-summary" to AnalyzerPrompts.MR_SUMMARY,
            "outdated-docs" to AnalyzerPrompts.OUTDATED_DOCS,
            "pr-split" to AnalyzerPrompts.PR_SPLIT,
            "risk-scoring" to AnalyzerPrompts.RISK_SCORING,
            "similar-issues" to AnalyzerPrompts.SIMILAR_ISSUES,
            "ownership-analysis" to AnalyzerPrompts.OWNERSHIP_ANALYSIS,
        )
        prompts.forEach { (name, text) -> assertTrue(text.length > 50, name) }
        assertEquals(prompts.size, prompts.values.toSet().size)
    }
}
