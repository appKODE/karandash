package io.karandash.engine

import io.karandash.api.finding.ComplianceItem
import io.karandash.api.finding.ComplianceStatus
import io.karandash.api.finding.LocationKey
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.finding.TimingData
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.state.RuleExecutionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportDataBuilderTest {
    @Test
    fun emptyFindings() {
        val result = ValidationResult(findings = emptyList())
        val report = ReportDataBuilder.build(result)

        assertTrue(report.passed)
        assertTrue(report.findings.isEmpty())
        assertEquals(0, report.summary.total)
        assertTrue(report.groupedByLocation.isEmpty())
        assertTrue(report.inlineEligible.isEmpty())
        assertTrue(report.summaryOnly.isEmpty())
        assertTrue(report.complianceChecklist.isEmpty())
    }

    @Test
    fun mixedSeveritiesSummary() {
        val findings = listOf(
            finding(Severity.ERROR, "title-format"),
            finding(Severity.WARNING, "branch-naming"),
            finding(Severity.INFO, "draft"),
            finding(Severity.ERROR, "commit-messages"),
        )
        val result = ValidationResult(findings = findings)
        val report = ReportDataBuilder.build(result)

        assertFalse(report.passed)
        assertEquals(4, report.findings.size)
        assertEquals(2, report.summary.errors)
        assertEquals(1, report.summary.warnings)
        assertEquals(1, report.summary.info)
    }

    @Test
    fun `findings keep the engine order when no finding sets a priority`() {
        val findings = listOf(finding(Severity.INFO, "z-rule"), finding(Severity.ERROR, "b-rule"), finding(Severity.WARNING, "a-rule"))
        val report = ReportDataBuilder.build(ValidationResult(findings = findings))

        assertEquals(listOf("z-rule", "b-rule", "a-rule"), report.findings.map { it.rule })
    }

    @Test
    fun `an explicit priority orders the report findings and ties fall back to the rule`() {
        val findings = listOf(
            finding(Severity.ERROR, "late").copy(priority = 50),
            finding(Severity.ERROR, "first").copy(priority = 1),
            finding(Severity.ERROR, "late-too").copy(priority = 50),
        )
        val report = ReportDataBuilder.build(ValidationResult(findings = findings))

        assertEquals(listOf("first", "late", "late-too"), report.findings.map { it.rule })
    }

    @Test
    fun inlineVsSummaryPartitioning() {
        val inlineFinding = Finding(
            severity = Severity.ERROR,
            message = "error in file",
            rule = "file-changes",
            location = Location.FileRef(path = "src/Main.kt", line = 42),
        )
        val fileNoLineFinding = Finding(
            severity = Severity.WARNING,
            message = "file-level warning",
            rule = "size-limits",
            location = Location.FileRef(path = "build.gradle.kts", line = null),
        )
        val commitFinding = Finding(
            severity = Severity.ERROR,
            message = "bad commit",
            rule = "commit-messages",
            location = Location.CommitRef(sha = "abc123"),
        )
        val noLocationFinding = Finding(
            severity = Severity.INFO,
            message = "general info",
            rule = "draft",
        )

        val result = ValidationResult(
            findings = listOf(inlineFinding, fileNoLineFinding, commitFinding, noLocationFinding)
        )
        val report = ReportDataBuilder.build(result)

        assertEquals(1, report.inlineEligible.size)
        assertEquals(inlineFinding, report.inlineEligible.first())

        assertEquals(3, report.summaryOnly.size)
        assertTrue(report.summaryOnly.contains(fileNoLineFinding))
        assertTrue(report.summaryOnly.contains(commitFinding))
        assertTrue(report.summaryOnly.contains(noLocationFinding))
    }

    @Test
    fun groupedByLocation() {
        val f1 = Finding(
            severity = Severity.ERROR,
            message = "first",
            rule = "rule-a",
            location = Location.FileRef(path = "src/Main.kt", line = 10),
        )
        val f2 = Finding(
            severity = Severity.WARNING,
            message = "second",
            rule = "rule-b",
            location = Location.FileRef(path = "src/Main.kt", line = 10),
        )
        val f3 = Finding(
            severity = Severity.INFO,
            message = "third",
            rule = "rule-c",
            location = Location.FileRef(path = "src/Other.kt", line = 5),
        )
        val f4 = Finding(
            severity = Severity.ERROR,
            message = "no location",
            rule = "rule-d",
        )

        val result = ValidationResult(findings = listOf(f1, f2, f3, f4))
        val report = ReportDataBuilder.build(result)

        assertEquals(2, report.groupedByLocation.size)
        assertEquals(
            listOf(f1, f2),
            report.groupedByLocation[LocationKey("src/Main.kt", 10)]
        )
        assertEquals(
            listOf(f3),
            report.groupedByLocation[LocationKey("src/Other.kt", 5)]
        )
    }

    @Test
    fun complianceChecklistCorrectness() {
        val findings = listOf(
            finding(Severity.ERROR, "title-format"),
            finding(Severity.WARNING, "title-format"),
            finding(Severity.WARNING, "branch-naming"),
            finding(Severity.INFO, "draft"),
        )
        val result = ValidationResult(findings = findings)
        val report = ReportDataBuilder.build(result)

        assertEquals(2, report.complianceChecklist.size)

        val map = report.complianceChecklist.associateBy { it.ruleId }
        assertEquals(ComplianceStatus.FAILED, map["title-format"]?.status)
        assertEquals(ComplianceStatus.WARNING, map["branch-naming"]?.status)
        assertNull(map["draft"])
    }

    @Test
    fun `a rule that ran clean is passed unless it was skipped and an INFO-only rule is not passed`() {
        val result = ValidationResult(
            findings = listOf(finding(Severity.INFO, "ai-incremental")),
            ruleExecutionResults = listOf(
                RuleExecutionResult("title-format", emptyList(), fromCache = false, executionDurationNs = 0),
                RuleExecutionResult(
                    "ai-incremental",
                    listOf(finding(Severity.INFO, "ai-incremental")),
                    fromCache = false,
                    executionDurationNs = 0
                ),
                RuleExecutionResult("branch-naming", emptyList(), fromCache = false, executionDurationNs = 0),
            ),
            skippedRules = listOf(SkippedRuleInfo("branch-naming", "unreachable")),
        )

        val checklist = ReportDataBuilder.build(result).complianceChecklist

        assertEquals(listOf(ComplianceItem("title-format", ComplianceStatus.PASSED)), checklist)
    }

    @Test
    fun explanationsPassedThrough() {
        val explanations = mapOf(
            "title-format" to "Title must follow conventional commits",
            "branch-naming" to "Branch names must use kebab-case",
        )
        val result = ValidationResult(findings = listOf(finding(Severity.ERROR, "title-format")))
        val report = ReportDataBuilder.build(result, explanations)

        assertEquals(explanations, report.explanations)
    }

    @Test
    fun metadataPopulated() {
        val result = ValidationResult(findings = emptyList())
        val report = ReportDataBuilder.build(result)

        assertEquals(io.karandash.Version.VERSION, report.metadata.version)
        assertTrue(report.metadata.timestamp > 0)
    }

    @Test
    fun passedTrueWhenNoErrors() {
        val findings = listOf(
            finding(Severity.WARNING, "branch-naming"),
            finding(Severity.INFO, "draft"),
        )
        val result = ValidationResult(findings = findings)
        val report = ReportDataBuilder.build(result)

        assertTrue(report.passed)
    }

    @Test
    fun fileRefWithNullLineGroupedCorrectly() {
        val f = Finding(
            severity = Severity.WARNING,
            message = "file-level",
            rule = "size-limits",
            location = Location.FileRef(path = "README.md", line = null),
        )
        val result = ValidationResult(findings = listOf(f))
        val report = ReportDataBuilder.build(result)

        assertEquals(1, report.groupedByLocation.size)
        assertEquals(
            listOf(f),
            report.groupedByLocation[LocationKey("README.md", null)]
        )
        assertTrue(report.inlineEligible.isEmpty())
        assertEquals(1, report.summaryOnly.size)
    }

    // ── Rule summaries ────────────────────────────────────────────────

    @Test
    fun ruleSummariesFromFindings() {
        val findings = listOf(
            finding(Severity.ERROR, "title-format"),
            finding(Severity.WARNING, "title-format"),
            finding(Severity.INFO, "branch-naming"),
            finding(Severity.ERROR, "branch-naming"),
        )
        val result = ValidationResult(findings = findings)
        val report = ReportDataBuilder.build(result)

        assertEquals(2, report.ruleSummaries.size)
        val titleSummary = report.ruleSummaries.first { it.ruleId == "title-format" }
        assertEquals(1, titleSummary.errors)
        assertEquals(1, titleSummary.warnings)
        assertEquals(0, titleSummary.infos)
        assertFalse(titleSummary.hasFixSuggestions)

        val branchSummary = report.ruleSummaries.first { it.ruleId == "branch-naming" }
        assertEquals(1, branchSummary.errors)
        assertEquals(0, branchSummary.warnings)
        assertEquals(1, branchSummary.infos)
    }

    @Test
    fun ruleSummaryDetectsFixSuggestions() {
        val findingWithFix = Finding(
            severity = Severity.WARNING,
            message = "should be draft",
            rule = "draft-check",
            fixSuggestions = listOf(FixSuggestion.SetDraft(draft = true)),
        )
        val result = ValidationResult(findings = listOf(findingWithFix))
        val report = ReportDataBuilder.build(result)

        assertEquals(1, report.ruleSummaries.size)
        assertTrue(report.ruleSummaries.first().hasFixSuggestions)
    }

    @Test
    fun ruleSummaryIncludesExecutionTiming() {
        val findings = listOf(finding(Severity.ERROR, "title-format"))
        val execResults = listOf(
            RuleExecutionResult(
                ruleId = "title-format",
                findings = findings,
                fromCache = false,
                executionDurationNs = 5_000_000L,
            ),
        )
        val result = ValidationResult(findings = findings, ruleExecutionResults = execResults)
        val report = ReportDataBuilder.build(result)

        val summary = report.ruleSummaries.first()
        assertEquals(5L, summary.executionTimeMs)
        assertFalse(summary.fromCache)
    }

    @Test
    fun ruleSummaryFromCachedExecution() {
        val findings = listOf(finding(Severity.INFO, "draft"))
        val execResults = listOf(
            RuleExecutionResult(
                ruleId = "draft",
                findings = findings,
                fromCache = true,
                executionDurationNs = 100_000L,
            ),
        )
        val result = ValidationResult(findings = findings, ruleExecutionResults = execResults)
        val report = ReportDataBuilder.build(result)

        assertTrue(report.ruleSummaries.first().fromCache)
    }

    @Test
    fun emptyFindingsProducesEmptyRuleSummaries() {
        val result = ValidationResult(findings = emptyList())
        val report = ReportDataBuilder.build(result)

        assertTrue(report.ruleSummaries.isEmpty())
    }

    // ── Metrics ─────────────────────────────────────────────────────────

    @Test
    fun metricsNullWithoutTimingOrExecutionResults() {
        val result = ValidationResult(findings = listOf(finding(Severity.INFO, "draft")))
        val report = ReportDataBuilder.build(result)

        assertNull(report.metrics)
    }

    @Test
    fun metricsPopulatedFromExecutionResults() {
        val findings = listOf(finding(Severity.ERROR, "r1"), finding(Severity.WARNING, "r2"))
        val execResults = listOf(
            RuleExecutionResult("r1", findings.take(1), fromCache = true, executionDurationNs = 2_000_000L),
            RuleExecutionResult("r2", findings.drop(1), fromCache = false, executionDurationNs = 8_000_000L),
        )
        val result = ValidationResult(findings = findings, ruleExecutionResults = execResults)
        val report = ReportDataBuilder.build(result)

        val metrics = assertNotNull(report.metrics)
        assertEquals(2L, metrics.ruleExecutionTimes["r1"])
        assertEquals(8L, metrics.ruleExecutionTimes["r2"])
        assertEquals(1, metrics.cacheHits)
        assertEquals(1, metrics.cacheMisses)
    }

    @Test
    fun metricsPopulatedFromTimingData() {
        val result = ValidationResult(findings = emptyList())
        val timingData = TimingData(
            totalDurationMs = 500,
            enrichmentFetchTimeMs = 100,
            reportGenerationTimeMs = 50,
        )
        val report = ReportDataBuilder.build(result, timingData = timingData)

        val metrics = assertNotNull(report.metrics)
        assertEquals(500L, metrics.totalDurationMs)
        assertEquals(100L, metrics.enrichmentFetchTimeMs)
        assertEquals(50L, metrics.reportGenerationTimeMs)
        assertEquals(0, metrics.cacheHits)
        assertEquals(0, metrics.cacheMisses)
    }

    @Test
    fun metricsWithBothTimingAndExecutionResults() {
        val findings = listOf(finding(Severity.ERROR, "r1"))
        val execResults = listOf(
            RuleExecutionResult("r1", findings, fromCache = false, executionDurationNs = 3_000_000L),
        )
        val timingData = TimingData(totalDurationMs = 200, enrichmentFetchTimeMs = 40)
        val result = ValidationResult(findings = findings, ruleExecutionResults = execResults)
        val report = ReportDataBuilder.build(result, timingData = timingData)

        val metrics = assertNotNull(report.metrics)
        assertEquals(200L, metrics.totalDurationMs)
        assertEquals(40L, metrics.enrichmentFetchTimeMs)
        assertEquals(3L, metrics.ruleExecutionTimes["r1"])
        assertEquals(0, metrics.cacheHits)
        assertEquals(1, metrics.cacheMisses)
    }

    // ── Backward compatibility ──────────────────────────────────────────

    @Test
    fun buildWithoutTimingDataPreservesExistingBehavior() {
        val findings = listOf(finding(Severity.ERROR, "title-format"))
        val result = ValidationResult(findings = findings)
        val report = ReportDataBuilder.build(result)

        assertFalse(report.passed)
        assertEquals(1, report.findings.size)
        assertNull(report.metrics)
        assertTrue(report.enrichmentUsed.isEmpty())
        assertNull(report.baselineComparison)
    }

    private fun finding(
        severity: Severity,
        rule: String
    ) = Finding(
        severity = severity,
        message = "test message for $rule",
        rule = rule,
    )
}
