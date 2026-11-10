package io.karandash.plugins.ai.pipeline

import io.karandash.models.MergeRequest
import io.karandash.models.ReviewState
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.config.AllowedOverride
import io.karandash.plugins.ai.core.config.GracePeriodEvaluator
import io.karandash.plugins.ai.core.config.GracePeriodWindowConfig
import io.karandash.plugins.ai.core.config.InlineMrOverride
import io.karandash.plugins.ai.core.config.InlineMrOverrideParser
import io.karandash.plugins.ai.core.config.InlineOverridesConfig
import io.karandash.plugins.ai.core.config.ViolationBaselineConfig
import io.karandash.plugins.ai.core.config.ViolationBudgetEvaluator
import io.karandash.sdk.Logger
import io.karandash.utils.GlobMatcher
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The finding policies driven by the MR description and repository files: inline overrides
 * (`ai.inlineOverrides` and the `karandash-override` tag), the `ai.gracePeriod` window and the
 * `ai.violationBudget` per-file baseline.
 */
internal class AiDescriptionPolicies(
    private val logger: Logger,
    private val now: () -> Instant = { Clock.System.now() },
) {
    /** Downgrades findings named by an inline override to INFO; `enabled: false` turns every form off. */
    fun applyInlineOverrides(
        findings: List<Finding>,
        mr: MergeRequest,
        config: InlineOverridesConfig?,
    ): List<Finding> {
        if (config?.enabled == false) return findings
        val parsed = InlineMrOverrideParser.parse(mr.description) + config?.let { taggedOverrides(mr, it) }.orEmpty()
        val overrides = allowedOnly(parsed, config?.allowed.orEmpty())
        if (overrides.isEmpty()) return findings

        logger.info("Inline MR overrides: ${overrides.size} override(s) found in description")
        val audit = config?.auditLog == true
        return findings.map { finding ->
            val path = (finding.location as? Location.FileRef)?.path
            val matched = overrides.firstOrNull { ov ->
                ov.rule == finding.rule &&
                    (ov.fields[PATH_FIELD]?.let { glob -> path != null && GlobMatcher.matches(path, glob) } ?: true)
            } ?: return@map finding
            if (audit) {
                logger.info(
                    "Inline override: [${finding.rule}] ${finding.severity} → INFO (reason: ${matched.reason})" +
                        " — ${finding.message.take(LOG_MSG_LIMIT)}",
                )
            }
            finding.copy(
                severity = Severity.INFO,
                message = "[OVERRIDDEN: ${matched.reason}] ${finding.message}",
                metadata = if (audit) finding.metadata + (OVERRIDE_REASON to matched.reason) else finding.metadata,
            )
        }
    }

    /** The overrides `ai.inlineOverrides.allowed` accepts (all when it is empty); each rejected one is logged. */
    private fun allowedOnly(
        overrides: List<InlineMrOverride>,
        allowed: List<AllowedOverride>,
    ): List<InlineMrOverride> {
        if (allowed.isEmpty()) return overrides
        val (valid, rejected) = InlineMrOverrideParser.validate(overrides, allowed).partition { it.valid }
        rejected.forEach { logger.warn("Inline override of '${it.override.rule}' not applied: ${it.rejectionReason}") }
        return valid.map { it.override }
    }

    private fun taggedOverrides(
        mr: MergeRequest,
        config: InlineOverridesConfig,
    ): List<InlineMrOverride> {
        val names = setOf(config.overrideTag.lowercase()) + EXTRA_OVERRIDE_TAGS
        val tags = InlineMrOverrideParser.parseTags(mr.description).filter { it.tag in names }
        if (tags.isEmpty()) return emptyList()
        if (config.requireApproval && mr.approvals.none { it.state == ReviewState.APPROVED }) {
            logger.warn("Inline overrides: ${tags.size} tag(s) ignored — requireApproval is set and the MR has no approval")
            return emptyList()
        }
        return tags.mapNotNull { tag -> tag.target?.let { InlineMrOverride(rule = it, fields = emptyMap(), reason = tag.reason) } }
    }

    /** While an `ai.gracePeriod` window is in force, every finding becomes INFO. */
    fun applyGraceWindow(
        findings: List<Finding>,
        mr: MergeRequest,
        config: GracePeriodWindowConfig?,
    ): List<Finding> {
        val window = config?.let { GracePeriodEvaluator.openWindow(mr, it, now()) } ?: return findings
        window.blockedReason?.let {
            logger.warn("Grace period requested but not in force: $it")
            return findings
        }
        if (config.auditLog) {
            logger.warn(
                "Grace period active: '${window.reason}' (approvals ${window.approvals}/${config.approvalRequired}," +
                    " expires ${window.expiresAt ?: "never"}); findings downgraded to INFO",
            )
        }
        return findings.map { finding ->
            if (finding.severity == Severity.INFO) {
                finding
            } else {
                finding.copy(severity = Severity.INFO, metadata = finding.metadata + (GRACE_FROM to finding.severity.name))
            }
        }
    }

    /** Applies the `ai.violationBudget` per-file baseline; an unreadable baseline file leaves findings as they are. */
    fun applyBaselineBudget(
        findings: List<Finding>,
        config: ViolationBaselineConfig?,
    ): List<Finding> {
        if (config == null || !config.enabled) return findings
        val baseline = ViolationBudgetEvaluator.loadBaseline(config.baselineFile).getOrElse {
            logger.warn("Violation budget: unreadable baseline '${config.baselineFile}': ${it.message}")
            return findings
        }
        val today = now().toLocalDateTime(TimeZone.UTC).date.toString()
        val result = ViolationBudgetEvaluator.applyBaseline(findings, config, baseline, today)
        result.overBudget.forEach { (path, counts) ->
            logger.info("Violation budget: $path OVER BUDGET (${counts.first} violations, allowance ${counts.second})")
        }
        logger.info("Violation budget: ${result.withinBudgetFiles} file(s) within baseline, ${result.overBudget.size} over")
        return result.findings
    }

    private companion object {
        const val PATH_FIELD = "path"
        const val LOG_MSG_LIMIT = 80
        const val OVERRIDE_REASON = "overrideReason"
        const val GRACE_FROM = "gracePeriodFrom"
        val EXTRA_OVERRIDE_TAGS = setOf("waive", "exempt")
    }
}
