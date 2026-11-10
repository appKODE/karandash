package io.karandash.plugins.ai.core.config

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.serialization.Serializable

/**
 * Severity reclassification: per-rule severity overrides with audit trail.
 *
 * Teams can override severity for specific rules and optionally suppress findings entirely.
 * Each reclassification is recorded in [Finding.metadata] for auditability.
 */
object SeverityReclassifier {
    private const val LOG_MSG_LIMIT = 80
    private const val RECLASSIFIED_FROM = "reclassifiedFrom"
    private const val RECLASSIFIED_REASON = "reclassifiedReason"

    /**
     * Applies severity overrides to a list of findings.
     *
     * @param findings The findings to process.
     * @param overrides The severity override rules to apply.
     * @param logger Logger for debug output of reclassification decisions.
     * @return A [ReclassificationResult] containing the processed findings and a count of changes.
     */
    fun apply(
        findings: List<Finding>,
        overrides: List<SeverityOverride>,
        logger: Logger = NoOpLogger,
    ): ReclassificationResult {
        if (overrides.isEmpty()) return ReclassificationResult(findings, 0)

        var reclassifiedCount = 0
        var suppressedCount = 0
        val own = findings.map { finding -> decide(finding, overrides, logger) }
        val result = findings.zip(own).mapNotNull { (finding, decided) ->
            val kept = followParent(finding, decided, findings, own, overrides)
            if (kept == null) {
                suppressedCount++
                logger.debug(
                    "Severity reclassify: suppressed [${finding.rule}] ${finding.severity}" +
                        " — ${finding.message.take(LOG_MSG_LIMIT)}",
                )
            }
            if (kept == null || kept.severity != finding.severity) reclassifiedCount++
            kept
        }

        val reclassifiedOnly = reclassifiedCount - suppressedCount
        if (reclassifiedCount > 0) {
            logger.debug(
                "Severity reclassify: $reclassifiedOnly reclassified, $suppressedCount suppressed" +
                    " out of ${findings.size} finding(s)",
            )
        }

        return ReclassificationResult(result, reclassifiedCount)
    }

    private fun decide(
        finding: Finding,
        overrides: List<SeverityOverride>,
        logger: Logger,
    ): Finding? {
        val override = overrides.find { it.matches(finding) && (it.suppress || it.reclassify.isNotEmpty()) }
        val reclassified = when {
            override == null -> finding
            override.suppress -> null
            else -> reclassify(finding, override, logger)
        }
        return reclassified?.takeIf { meetsMinimum(it, overrides) }
    }

    /**
     * A code suggestion no override matched shares the fate of the review comment it pairs with
     * ([AiMetadataTransformer.pairsWith]): dropped with it, or moved to its new severity.
     */
    private fun followParent(
        finding: Finding,
        decided: Finding?,
        findings: List<Finding>,
        own: List<Finding?>,
        overrides: List<SeverityOverride>,
    ): Finding? {
        if (finding.rule != AiRuleIds.CODE_SUGGESTIONS || decided !== finding || overrides.any { it.matches(finding) }) return decided
        val parent = findings.indices.firstOrNull { i ->
            findings[i].rule == AiRuleIds.REVIEW_COMMENTS && findings[i].location != null &&
                AiMetadataTransformer.pairsWith(finding, findings[i])
        } ?: return decided
        val parentAfter = own[parent] ?: return null
        if (parentAfter.severity == findings[parent].severity) return decided
        return finding.copy(
            severity = parentAfter.severity,
            metadata = finding.metadata + mapOf(
                RECLASSIFIED_FROM to finding.severity.name,
                RECLASSIFIED_REASON to (parentAfter.metadata[RECLASSIFIED_REASON] ?: "review comment reclassified"),
            ),
        )
    }

    private fun reclassify(
        finding: Finding,
        override: SeverityOverride,
        logger: Logger,
    ): Finding {
        val newSeverity = override.reclassify[finding.severity.name.lowercase()]
            ?.let { Severity.fromString(it, default = finding.severity) }
            ?: return finding
        if (newSeverity == finding.severity) return finding
        val reason = override.reason ?: "severity override"
        logger.debug(
            "Severity reclassify: [${finding.rule}] ${finding.severity} → $newSeverity" +
                " (reason: $reason) — ${finding.message.take(LOG_MSG_LIMIT)}",
        )
        return finding.copy(
            severity = newSeverity,
            metadata = finding.metadata + mapOf(
                RECLASSIFIED_FROM to finding.severity.name,
                RECLASSIFIED_REASON to reason,
            ),
        )
    }

    /** False when a matching override's `minimumSeverity` is above the finding's severity. */
    private fun meetsMinimum(
        finding: Finding,
        overrides: List<SeverityOverride>,
    ): Boolean =
        overrides.none { override ->
            val minimum = override.minimumSeverity?.let { Severity.fromString(it, default = Severity.INFO) }
            minimum != null && override.matches(finding) && finding.severity.ordinal > minimum.ordinal
        }
}

/** Defines a severity override for findings matching a given rule. */
@Serializable
data class SeverityOverride(
    /** Substring matched against [Finding.rule] (an `ai-` prefix is optional); `*` matches every rule. */
    val rule: String,
    /**
     * Maps from existing severity name (lowercase) to replacement severity name.
     * Example: `mapOf("error" to "warning")`.
     */
    val reclassify: Map<String, String> = emptyMap(),
    /** When true, matching findings are removed from results entirely. */
    val suppress: Boolean = false,
    /** Human-readable reason recorded in the finding's metadata audit trail. */
    val reason: String? = null,
    /** If set, only findings whose message contains this string (case-insensitive) are matched. */
    val whenMessageContains: String? = null,
    /** Matching findings less severe than this (after reclassification) are dropped. */
    val minimumSeverity: String? = null,
) {
    /**
     * Returns true when this override applies to the given finding.
     *
     * @param finding The finding to test.
     */
    fun matches(finding: Finding): Boolean {
        // AI findings carry the bare analyzer id (`review-comments`); accept the `ai-` feature alias too
        if (rule != WILDCARD && !finding.rule.contains(rule) && !finding.rule.contains(rule.removePrefix(AI_PREFIX))) return false
        if (whenMessageContains != null && !finding.message.contains(whenMessageContains, ignoreCase = true)) return false
        return true
    }

    private companion object {
        const val WILDCARD = "*"
        const val AI_PREFIX = "ai-"
    }
}

/** The outcome of applying severity overrides to a finding list. */
@Serializable
data class ReclassificationResult(
    /** Findings after suppression and reclassification. */
    val findings: List<Finding>,
    /** Number of findings that were suppressed or had their severity changed. */
    val reclassifiedCount: Int,
)
