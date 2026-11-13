package io.karandash.engine

import io.karandash.Version
import io.karandash.api.finding.BaselineComparison
import io.karandash.api.finding.ComplianceItem
import io.karandash.api.finding.ComplianceStatus
import io.karandash.api.finding.LocationKey
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.RuleSummary
import io.karandash.api.finding.TimingData
import io.karandash.api.finding.ValidationMetrics
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.finding.errorsCount
import io.karandash.models.finding.groupedByRule
import io.karandash.models.finding.sortedByPriority
import io.karandash.state.RuleExecutionResult
import kotlin.time.Clock

/**
 * Builds a [ValidationReport] from a [ValidationResult] and supplementary data.
 *
 * This is a pre-reporter pipeline stage: it runs after validation (and optional
 * AI enrichment) completes, and before reporter dispatch. All reporters receive
 * the same fully-enriched [ValidationReport], eliminating duplicated aggregation
 * logic across reporter implementations.
 */
object ReportDataBuilder {
    private const val NS_PER_MS = 1_000_000L

    /**
     * Builds a fully-enriched [ValidationReport].
     *
     * @param result The validation result containing findings.
     * @param explanations Rule ID to explanation text map (from [ModuleDefaultsCollector]).
     * @param timingData Optional external timing data from the CLI pipeline.
     * @return A [ValidationReport] with all pre-computed enrichments.
     */
    fun build(
        result: ValidationResult,
        explanations: Map<String, String> = emptyMap(),
        timingData: TimingData? = null,
        changedFiles: Set<String>? = null,
        baselineComparison: BaselineComparison? = null,
    ): ValidationReport {
        // Report order stays the engine order unless a rule set a priority; then priority, severity and rule decide.
        val findings = result.findings.let { all ->
            if (all.any { it.priority != Finding.DEFAULT_PRIORITY }) all.sortedByPriority() else all
        }
        val summary = findings.summarize()

        val (inlineEligible, summaryOnly) = findings.partition { finding ->
            val loc = finding.location
            loc is Location.FileRef && loc.line != null
        }

        val groupedByLocation = findings
            .filter { it.location is Location.FileRef }
            .groupBy { finding ->
                val loc = finding.location as Location.FileRef
                LocationKey(path = loc.path, line = loc.line)
            }

        val complianceChecklist = buildComplianceChecklist(
            findings,
            executed = result.ruleExecutionResults.mapTo(mutableSetOf()) { it.ruleId },
            skipped = result.skippedRules.mapTo(mutableSetOf()) { it.ruleId },
        )
        val ruleSummaries = buildRuleSummaries(findings, result.ruleExecutionResults)
        val metrics = buildMetrics(result, timingData)

        return ValidationReport(
            passed = result.passed,
            findings = findings,
            summary = summary,
            groupedByLocation = groupedByLocation,
            inlineEligible = inlineEligible,
            summaryOnly = summaryOnly,
            complianceChecklist = complianceChecklist,
            explanations = explanations,
            metadata = ReportMetadata(
                version = Version.VERSION,
                timestamp = Clock.System.now().epochSeconds,
            ),
            appliedPolicies = result.appliedPolicies,
            skippedRules = result.skippedRules,
            metrics = metrics,
            ruleSummaries = ruleSummaries,
            changedFiles = changedFiles,
            baselineComparison = baselineComparison,
            provenance = result.provenance,
        )
    }

    /**
     * One item per rule with a WARNING or ERROR finding, and per rule that ran clean: executed, not skipped, and with
     * no finding at all. A rule whose only findings are INFO is left out, so it is not listed as passed.
     */
    private fun buildComplianceChecklist(
        findings: List<Finding>,
        executed: Set<String>,
        skipped: Set<String>,
    ): List<ComplianceItem> {
        val flagged = findings.filter { it.severity != Severity.INFO }
        val cleanRules = executed - skipped - findings.mapTo(mutableSetOf()) { it.rule }
        val ruleIds = (flagged.map { it.rule } + cleanRules.sorted()).distinct()
        val errorRules = flagged
            .filter { it.severity == Severity.ERROR }
            .map { it.rule }
            .toSet()
        val warningRules = flagged
            .filter { it.severity == Severity.WARNING }
            .map { it.rule }
            .toSet()

        return ruleIds.map { ruleId ->
            val status = when (ruleId) {
                in errorRules -> ComplianceStatus.FAILED
                in warningRules -> ComplianceStatus.WARNING
                else -> ComplianceStatus.PASSED
            }
            ComplianceItem(ruleId = ruleId, status = status)
        }
    }

    private fun buildRuleSummaries(
        findings: List<Finding>,
        ruleExecutionResults: List<RuleExecutionResult>,
    ): List<RuleSummary> {
        val byRule = findings.groupedByRule()
        val executionByRule = ruleExecutionResults.associateBy { it.ruleId }

        return byRule.map { (ruleId, ruleFindings) ->
            val execution = executionByRule[ruleId]
            RuleSummary(
                ruleId = ruleId,
                errors = ruleFindings.errorsCount(),
                warnings = ruleFindings.count { it.severity == Severity.WARNING },
                infos = ruleFindings.count { it.severity == Severity.INFO },
                hasFixSuggestions = ruleFindings.any { it.fixSuggestions.isNotEmpty() },
                executionTimeMs = execution?.executionDurationNs?.div(NS_PER_MS) ?: 0,
                fromCache = execution?.fromCache ?: false,
            )
        }
    }

    private fun buildMetrics(
        result: ValidationResult,
        timingData: TimingData?,
    ): ValidationMetrics? {
        if (timingData == null && result.ruleExecutionResults.isEmpty()) return null

        val ruleExecutionTimes = result.ruleExecutionResults.associate {
            it.ruleId to (it.executionDurationNs / NS_PER_MS)
        }
        val cacheHits = result.ruleExecutionResults.count { it.fromCache }
        val cacheMisses = result.ruleExecutionResults.count { !it.fromCache }

        return ValidationMetrics(
            totalDurationMs = timingData?.totalDurationMs ?: 0,
            ruleExecutionTimes = ruleExecutionTimes,
            enrichmentFetchTimeMs = timingData?.enrichmentFetchTimeMs ?: 0,
            reportGenerationTimeMs = timingData?.reportGenerationTimeMs ?: 0,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
        )
    }
}
