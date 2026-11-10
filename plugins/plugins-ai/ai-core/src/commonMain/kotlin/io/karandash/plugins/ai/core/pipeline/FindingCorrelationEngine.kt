package io.karandash.plugins.ai.core.pipeline

import io.karandash.helpers.RuleIds
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.finding.withFixedText
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.isAiFinding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Post-validation stage that detects compound risk patterns across findings from different rules.
 *
 * Evaluates a list of [CorrelationPattern] definitions against the full findings set.
 * When a pattern matches, a synthetic meta-finding is generated with escalated severity
 * to surface compound risks that no individual rule can detect in isolation.
 *
 * @param patterns Correlation patterns to evaluate. Defaults to [DEFAULT_PATTERNS].
 */
class FindingCorrelationEngine(
    private val patterns: List<CorrelationPattern> = DEFAULT_PATTERNS,
) {
    /**
     * Evaluates all configured patterns against the given findings and appends any
     * generated meta-findings to the result.
     *
     * @param findings All validation findings collected so far.
     * @return A [CorrelationResult] containing the original findings plus any meta-findings.
     */
    fun correlate(findings: List<Finding>): CorrelationResult {
        val ruleIds = findings.groupBy { it.rule }.keys
        val matched = patterns.filter { evaluatePattern(it, ruleIds, findings) }
        val upgraded = matched.filter { it.upgradeMatched }.fold(findings) { acc, pattern -> upgradeMatched(acc, pattern) }
        val metaFindings = matched.map { pattern ->
            Finding(
                severity = Severity.fromString(pattern.severity, default = Severity.WARNING),
                message = pattern.message,
                suggestion = pattern.suggestion,
                rule = "correlation:${pattern.name}",
                metadata = mapOf(
                    "correlationType" to "compound-risk",
                    "matchedRules" to
                        pattern.requiredRules.filter { required -> ruleIds.any { ruleMatches(it, required) } }.joinToString(","),
                ),
            ).withFixedText(CorrelationText.entries)
        }

        return CorrelationResult(
            findings = upgraded + metaFindings,
            metaFindingsCount = metaFindings.size,
            patternsEvaluated = patterns.size,
        )
    }

    /**
     * `ai.correlation`: collapses findings that different rules raised on the same file line into
     * one (AI rules only; deterministic rule findings are never folded away). The highest-severity
     * (then highest-confidence) finding is kept and lists every rule that
     * reported the spot under [AiFindingMetadataKeys.CORRELATED_SOURCES]; file-level findings and
     * findings from a single rule are left alone.
     */
    fun mergeSameLocation(findings: List<Finding>): List<Finding> {
        val groups = findings.groupBy { finding ->
            val ref = finding.location as? Location.FileRef
            if (!finding.isAiFinding() || ref?.line == null) null else ref.path to ref.line
        }
        val replaced = mutableMapOf<Finding, Finding?>()
        for ((location, group) in groups) {
            val rules = group.map { it.rule }.distinct()
            if (location == null || rules.size < 2) continue
            val primary = group.maxWith(compareBy<Finding> { severityRank(it.severity) }.thenBy { confidenceOf(it) })
            group.forEach { replaced[it] = null }
            replaced[primary] = primary.copy(
                metadata = primary.metadata + (AiFindingMetadataKeys.CORRELATED_SOURCES to rules.sorted().joinToString(",")),
            )
        }
        return findings.mapNotNull { if (it in replaced) replaced[it] else it }
    }

    private fun upgradeMatched(
        findings: List<Finding>,
        pattern: CorrelationPattern,
    ): List<Finding> {
        val target = Severity.fromString(pattern.severity, default = Severity.WARNING)
        return findings.map { finding ->
            val matched = pattern.requiredRules.any { ruleMatches(finding.rule, it) }
            if (!matched || severityRank(finding.severity) >= severityRank(target)) {
                finding
            } else {
                finding.copy(severity = target, metadata = finding.metadata + (UPGRADED_BY to pattern.name))
            }
        }
    }

    private fun evaluatePattern(
        pattern: CorrelationPattern,
        ruleIds: Set<String>,
        findings: List<Finding>,
    ): Boolean {
        val requiredMet = pattern.requiredRules.all { required ->
            ruleIds.any { ruleMatches(it, required) }
        }
        if (!requiredMet) return false

        val absentMet = pattern.absentRules.none { absent ->
            ruleIds.any { ruleMatches(it, absent) }
        }
        if (!absentMet) return false

        if (pattern.minTotalFindings > 0 && findings.size < pattern.minTotalFindings) return false

        if (pattern.minSeverity != null) {
            val minSev = Severity.fromString(pattern.minSeverity, default = Severity.INFO)
            val hasMinSeverity = findings.any { severityRank(it.severity) >= severityRank(minSev) }
            if (!hasMinSeverity) return false
        }

        return true
    }

    companion object {
        /**
         * Built-in patterns that detect common compound risk scenarios. They run without any config, so they stay at
         * [DEFAULT_SEVERITY]: an error here would fail a run on findings that are only warnings; `ai.correlation.patterns`
         * sets a stricter one.
         */
        val DEFAULT_PATTERNS: List<CorrelationPattern> = listOf(
            CorrelationPattern(
                name = "high-risk-unreviewed",
                requiredRules = listOf(SIZE_LIMITS),
                absentRules = listOf(RuleIds.APPROVAL),
                message = CorrelationText.HIGH_RISK_UNREVIEWED.en,
                suggestion = CorrelationText.HIGH_RISK_UNREVIEWED_FIX.en,
                severity = DEFAULT_SEVERITY,
            ),
            CorrelationPattern(
                name = "large-undocumented",
                requiredRules = listOf(SIZE_LIMITS, DESCRIPTION_FORMAT),
                message = CorrelationText.LARGE_UNDOCUMENTED.en,
                suggestion = CorrelationText.LARGE_UNDOCUMENTED_FIX.en,
                severity = DEFAULT_SEVERITY,
            ),
            CorrelationPattern(
                name = "security-without-review",
                requiredRules = listOf(SECURITY),
                absentRules = listOf(RuleIds.APPROVAL),
                message = CorrelationText.SECURITY_WITHOUT_REVIEW.en,
                suggestion = CorrelationText.SECURITY_WITHOUT_REVIEW_FIX.en,
                severity = DEFAULT_SEVERITY,
            ),
        )

        // Rule ids of rules this tier cannot depend on; ruleMatches ignores case and separators, so a kebab-case id matches too
        private const val SIZE_LIMITS = "sizeLimits"
        private const val DESCRIPTION_FORMAT = "descriptionFormat"
        private const val SECURITY = "security"

        /** Metadata key naming the correlation pattern that raised a finding's severity. */
        const val UPGRADED_BY = "correlationUpgradedBy"

        /** Severity a pattern's meta-finding gets when none is configured. */
        const val DEFAULT_SEVERITY = "warning"
    }
}

/**
 * Whether the finding rule [rule] is the rule [pattern] names, or one of its sub-rules (`security:secrets`): both are
 * compared in [ModuleIdNormalizer] form, so `size-limits`, `size_limits` and `sizeLimits` are the same rule.
 */
internal fun ruleMatches(
    rule: String,
    pattern: String,
): Boolean = ModuleIdNormalizer.normalize(rule).contains(ModuleIdNormalizer.normalize(pattern))

/** Orders severities INFO < WARNING < ERROR. */
internal fun severityRank(severity: Severity): Int =
    when (severity) {
        Severity.ERROR -> 2
        Severity.WARNING -> 1
        Severity.INFO -> 0
    }

private fun confidenceOf(finding: Finding): Double = finding.metadata[AiFindingMetadataKeys.CONFIDENCE]?.toDoubleOrNull() ?: 0.0

/**
 * Defines a compound risk pattern that the [FindingCorrelationEngine] evaluates.
 *
 * A pattern matches when all of the following conditions hold:
 * - Every rule in [requiredRules] has at least one finding.
 * - No rule in [absentRules] has any finding.
 * - If [minTotalFindings] > 0, the total finding count meets or exceeds it.
 * - If [minSeverity] is set, at least one finding meets or exceeds that severity.
 *
 * @param name Unique identifier for this pattern; used in the meta-finding rule (`correlation:<name>`).
 * @param requiredRules Rule IDs (or substrings) that must have findings for the pattern to fire.
 * @param absentRules Rule IDs (or substrings) that must have no findings for the pattern to fire.
 * @param message Human-readable description emitted in the generated meta-finding.
 * @param suggestion How to resolve the risk, shown as the meta-finding's "How to fix".
 * @param severity Severity of the generated meta-finding (`"error"`, `"warning"`, or `"info"`).
 * @param minTotalFindings Minimum total number of findings required; 0 means no minimum.
 * @param minSeverity Minimum severity that at least one finding must reach; null means no requirement.
 */
@Serializable
data class CorrelationPattern(
    val name: String,
    val requiredRules: List<String> = emptyList(),
    val absentRules: List<String> = emptyList(),
    val message: String,
    val suggestion: String? = null,
    val severity: String = FindingCorrelationEngine.DEFAULT_SEVERITY,
    val minTotalFindings: Int = 0,
    val minSeverity: String? = null,
    /** Raise the matched rules' findings to [severity] when it is higher (set for `ai.correlation.patterns`). */
    val upgradeMatched: Boolean = false,
)

/**
 * `ai.correlation` block. Present and enabled, findings at the same location are merged across
 * analyzers and [patterns] run next to [FindingCorrelationEngine.DEFAULT_PATTERNS]; `enabled: false`
 * turns correlation off. Absent, only the default patterns run, as before.
 */
@Serializable
data class CorrelationConfig(
    val enabled: Boolean = true,
    val patterns: List<CorrelationPatternConfig> = emptyList(),
)

/**
 * One `ai.correlation.patterns` entry: when every rule in [findings] reported something (and
 * [whenCondition], a conditions-engine expression over the MR, holds), a meta-finding is added and
 * those rules' findings are raised to [severity].
 */
@Serializable
data class CorrelationPatternConfig(
    val name: String,
    val findings: List<String> = emptyList(),
    val severity: String = FindingCorrelationEngine.DEFAULT_SEVERITY,
    val message: String? = null,
    @SerialName("when")
    val whenCondition: String? = null,
) {
    fun toPattern(): CorrelationPattern =
        CorrelationPattern(
            name = name,
            requiredRules = findings,
            message = message ?: "Correlated findings: ${findings.joinToString(" + ")}",
            suggestion = "Resolve the correlated findings: ${findings.joinToString(", ")}.",
            severity = severity,
            upgradeMatched = true,
        )
}

/**
 * Result produced by [FindingCorrelationEngine.correlate].
 *
 * @param findings Original findings plus any generated meta-findings.
 * @param metaFindingsCount Number of meta-findings generated in this run.
 * @param patternsEvaluated Number of patterns that were evaluated.
 */
@Serializable
data class CorrelationResult(
    val findings: List<Finding>,
    val metaFindingsCount: Int,
    val patternsEvaluated: Int,
)
