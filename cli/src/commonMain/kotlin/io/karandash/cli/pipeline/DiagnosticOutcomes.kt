package io.karandash.cli.pipeline

import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.SkipCategory
import io.karandash.diagnostics.FindingDiagnostic
import io.karandash.diagnostics.RuleOutcomeDiagnostic
import io.karandash.engine.ValidationResult
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.normalize.ConfigKeyNormalizer.kebabToCamelCase

/** Turns a [ValidationResult] into the per-rule outcomes and findings of `diagnostic.json`, rule ids camelCase. */
internal object DiagnosticOutcomes {
    const val OK = "ok"
    const val SKIPPED = "skipped"
    const val ERROR = "error"
    const val NOT_RUN = "not_run"

    /** Every rule of the run, camelCase and sorted: [configured] keys plus each rule that ran, skipped or found. */
    fun ruleIds(
        configured: Collection<String>,
        result: ValidationResult?,
    ): List<String> {
        val seen = configured.toMutableSet()
        if (result != null) {
            seen += result.ruleExecutionResults.map { it.ruleId }
            seen += result.skippedRules.map { it.ruleId }
            seen += result.provenance
                ?.analyzers
                .orEmpty()
                .map { it.ruleId }
            seen += result.findings.map { it.rule }
        }
        return seen.map(::kebabToCamelCase).distinct().sorted()
    }

    fun rules(
        configured: Collection<String>,
        result: ValidationResult,
    ): List<RuleOutcomeDiagnostic> {
        val findingCounts = result.findings.groupingBy { kebabToCamelCase(it.rule) }.eachCount()
        val executed = result.ruleExecutionResults.associateBy { kebabToCamelCase(it.ruleId) }
        val skipped = result.skippedRules.associateBy { kebabToCamelCase(it.ruleId) }
        val analyzers = result.provenance
            ?.analyzers
            .orEmpty()
            .associateBy { kebabToCamelCase(it.ruleId) }
        return ruleIds(configured, result).map { id ->
            val skip = skipped[id]
            val analyzer = analyzers[id]
            val ran = analyzer?.status == ProvenanceStatus.OK
            val failed = analyzer?.status == ProvenanceStatus.FAILED || skip?.category == SkipCategory.AI_PROVIDER_ERROR
            val status =
                when {
                    // the analyzer's calls completed: a partial chunk failure is its reason, not the rule's status
                    ran -> OK

                    failed -> ERROR

                    skip != null || analyzer?.status == ProvenanceStatus.SKIPPED -> SKIPPED

                    id in executed || analyzer != null || id in findingCounts -> OK

                    else -> NOT_RUN
                }
            RuleOutcomeDiagnostic(
                ruleId = id,
                status = status,
                reason = skip?.reason?.ifBlank { null } ?: analyzer?.reason,
                category = skip?.category?.name,
                fromCache = executed[id]?.fromCache ?: false,
                findingCount = findingCounts[id] ?: 0,
                model = analyzer?.model,
                inputTokens = analyzer?.inputTokens,
                outputTokens = analyzer?.outputTokens,
                costUsd = analyzer?.costUsd,
            )
        }
    }

    fun findings(findings: List<Finding>): List<FindingDiagnostic> =
        findings.map { f ->
            val location = f.location
            FindingDiagnostic(
                rule = kebabToCamelCase(f.rule),
                severity = f.severity.name.lowercase(),
                message = f.message,
                file = (location as? Location.FileRef)?.path,
                line = (location as? Location.FileRef)?.line,
                commit = (location as? Location.CommitRef)?.sha,
            )
        }
}
