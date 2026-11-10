package io.karandash.cli.pipeline

import io.karandash.api.finding.AnalyzerProvenance
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.engine.ValidationResult
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.state.RuleExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticOutcomesTest {
    private val result =
        ValidationResult(
            findings = listOf(
                Finding(Severity.ERROR, "no body", "commit-body", Location.CommitRef("abc")),
                Finding(Severity.WARNING, "long", "titleFormat", Location.FileRef("a.kt", 3)),
            ),
            skippedRules = listOf(
                SkippedRuleInfo("labels", "when: false", SkipCategory.WHEN_CONDITION),
                SkippedRuleInfo("ai-review", "provider down", SkipCategory.AI_PROVIDER_ERROR),
            ),
            ruleExecutionResults = listOf(
                RuleExecutionResult("titleFormat", emptyList(), fromCache = true, executionDurationNs = 1),
                RuleExecutionResult("commit-body", emptyList(), fromCache = false, executionDurationNs = 1),
            ),
            provenance = ReviewProvenance(
                analyzers = listOf(
                    AnalyzerProvenance("mr-summary", ProvenanceStatus.OK, model = "m", inputTokens = 10, outputTokens = 2, costUsd = 0.5),
                    AnalyzerProvenance("ai-review", ProvenanceStatus.FAILED, reason = "500"),
                ),
            ),
        )

    @Test
    fun `rule ids are the camelCase union of configured and seen rules`() {
        val ids = DiagnosticOutcomes.ruleIds(setOf("commitMessages", "titleFormat"), result)
        assertEquals(listOf("aiReview", "commitBody", "commitMessages", "labels", "mrSummary", "titleFormat"), ids)
    }

    @Test
    fun `each rule gets ok skipped error or not_run with tokens and cost`() {
        val rules = DiagnosticOutcomes.rules(setOf("commitMessages"), result).associateBy { it.ruleId }
        assertEquals("error", rules.getValue("aiReview").status)
        assertEquals("ok", rules.getValue("commitBody").status)
        assertEquals(1, rules.getValue("commitBody").findingCount)
        assertEquals("not_run", rules.getValue("commitMessages").status)
        assertEquals("skipped", rules.getValue("labels").status)
        assertEquals("WHEN_CONDITION", rules.getValue("labels").category)
        assertEquals("when: false", rules.getValue("labels").reason)
        assertEquals(0.5, rules.getValue("mrSummary").costUsd)
        assertEquals(10, rules.getValue("mrSummary").inputTokens)
        assertEquals(true, rules.getValue("titleFormat").fromCache)
    }

    @Test
    fun `an analyzer whose calls completed is ok despite a provider error skip`() {
        val partial =
            ValidationResult(
                findings = emptyList(),
                skippedRules = listOf(SkippedRuleInfo("ai-review", "1 chunk skipped: timeout", SkipCategory.AI_PROVIDER_ERROR)),
                provenance = ReviewProvenance(analyzers = listOf(AnalyzerProvenance("ai-review", ProvenanceStatus.OK, model = "m"))),
            )

        val rule = DiagnosticOutcomes.rules(emptySet(), partial).single()

        assertEquals("ok", rule.status)
        assertEquals("1 chunk skipped: timeout", rule.reason)
    }

    @Test
    fun `findings carry rule severity and location`() {
        val findings = DiagnosticOutcomes.findings(result.findings)
        assertEquals("commitBody", findings[0].rule)
        assertEquals("abc", findings[0].commit)
        assertEquals("warning", findings[1].severity)
        assertEquals("a.kt", findings[1].file)
        assertEquals(3, findings[1].line)
    }
}
