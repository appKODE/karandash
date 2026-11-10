package io.karandash.builtin

import io.karandash.plugins.ai.analyzers.changecategorization.ChangeCategorizationAnalyzerModule
import io.karandash.plugins.ai.analyzers.codequality.CodeQualityAnalyzerModule
import io.karandash.plugins.ai.analyzers.codesuggestions.CodeSuggestionsAnalyzerModule
import io.karandash.plugins.ai.analyzers.commentanalysis.CommentAnalysisAnalyzerModule
import io.karandash.plugins.ai.analyzers.descriptionquality.DescriptionQualityAnalyzerModule
import io.karandash.plugins.ai.analyzers.designdocdrift.DesignDocDriftAnalyzerModule
import io.karandash.plugins.ai.analyzers.fixsuggestions.FixSuggestionsAnalyzerModule
import io.karandash.plugins.ai.analyzers.generatedescription.GenerateDescriptionAnalyzerModule
import io.karandash.plugins.ai.analyzers.mrsummary.MrSummaryAnalyzerModule
import io.karandash.plugins.ai.analyzers.outdateddocs.OutdatedDocsAnalyzerModule
import io.karandash.plugins.ai.analyzers.ownership.OwnershipAnalyzerModule
import io.karandash.plugins.ai.analyzers.prsplit.PrSplitAnalyzerModule
import io.karandash.plugins.ai.analyzers.riskscoring.RiskScoringAnalyzerModule
import io.karandash.plugins.ai.analyzers.similarissues.SimilarIssuesAnalyzerModule
import io.karandash.plugins.ai.prompts.AnalyzerPrompts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** each analyzer's built-in prompt comes from the `prompts` module, one prompt per analyzer. */
class Dead15AnalyzerPromptsTest {
    @Test
    fun `every analyzer default prompt is its prompts module entry`() {
        val pairs = listOf(
            ChangeCategorizationAnalyzerModule().defaultPrompt to AnalyzerPrompts.CHANGE_CATEGORIZATION,
            CodeQualityAnalyzerModule().defaultPrompt to AnalyzerPrompts.CODE_QUALITY,
            CodeSuggestionsAnalyzerModule().defaultPrompt to AnalyzerPrompts.CODE_SUGGESTIONS,
            CommentAnalysisAnalyzerModule().defaultPrompt to AnalyzerPrompts.COMMENT_ANALYSIS,
            DescriptionQualityAnalyzerModule().defaultPrompt to AnalyzerPrompts.DESCRIPTION_QUALITY,
            DesignDocDriftAnalyzerModule().defaultPrompt to AnalyzerPrompts.DESIGN_DOC_DRIFT,
            FixSuggestionsAnalyzerModule().defaultPrompt to AnalyzerPrompts.FIX_SUGGESTIONS,
            GenerateDescriptionAnalyzerModule().defaultPrompt to AnalyzerPrompts.GENERATE_DESCRIPTION,
            MrSummaryAnalyzerModule().defaultPrompt to AnalyzerPrompts.MR_SUMMARY,
            OutdatedDocsAnalyzerModule().defaultPrompt to AnalyzerPrompts.OUTDATED_DOCS,
            OwnershipAnalyzerModule().defaultPrompt to AnalyzerPrompts.OWNERSHIP_ANALYSIS,
            PrSplitAnalyzerModule().defaultPrompt to AnalyzerPrompts.PR_SPLIT,
            RiskScoringAnalyzerModule().defaultPrompt to AnalyzerPrompts.RISK_SCORING,
            SimilarIssuesAnalyzerModule().defaultPrompt to AnalyzerPrompts.SIMILAR_ISSUES,
        )

        pairs.forEach { (actual, expected) -> assertEquals(expected, actual) }
        assertEquals(pairs.size, pairs.map { it.second }.toSet().size, "one distinct prompt per analyzer")
        assertTrue(pairs.all { it.second.startsWith("You are") }, "prompt text moved whole")
    }
}
