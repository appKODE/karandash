package io.karandash.plugins.ai.pipeline

import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MrCacheKey
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.architecture.AdrValidator
import io.karandash.plugins.ai.core.config.AuthorSeniorityResolver
import io.karandash.plugins.ai.core.config.ConditionalSectionChecker
import io.karandash.plugins.ai.core.config.EnforcementMode
import io.karandash.plugins.ai.core.config.EnvironmentTrackResolver
import io.karandash.plugins.ai.core.config.GracePeriodConfig
import io.karandash.plugins.ai.core.config.GracePeriodEvaluator
import io.karandash.plugins.ai.core.config.ProgressiveEnforcement
import io.karandash.plugins.ai.core.config.RuleComposer
import io.karandash.plugins.ai.core.config.RuleMacroExpander
import io.karandash.plugins.ai.core.config.SeverityReclassifier
import io.karandash.plugins.ai.core.config.TeamNamespaceResolver
import io.karandash.plugins.ai.core.config.ViolationBudgetEvaluator
import io.karandash.sdk.Logger
import kotlinx.atomicfu.atomic
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Applies config-driven features to the finding list in order:
 * progressive enforcement, severity reclassification, inline MR overrides,
 * grace period detection, conditional sections, rule composition, author seniority,
 * team scoping, and ADR validation.
 *
 * @param persistence AI state that counts grace-period uses (`maxDuration`, `maxUsesPerDay`); without it they are not enforced.
 */
internal class AiConfigFeatureApplicator(
    private val logger: Logger,
    private val policies: AiDescriptionPolicies = AiDescriptionPolicies(logger),
    private val persistence: AiStatePersistence? = null,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val warnedNoGraceState = atomic(false)

    /**
     * Applies all config-driven feature transformations to findings.
     */
    fun apply(
        findings: List<Finding>,
        mr: MergeRequest,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        var result = findings

        result = expandMacros(result, aiConfig)
        result = applyProgressiveEnforcement(result, aiConfig)
        result = applySeverityReclassification(result, aiConfig)
        result = applyViolationBudgets(result, aiConfig)
        result = policies.applyBaselineBudget(result, aiConfig.violationBudget)
        result = policies.applyInlineOverrides(result, mr, aiConfig.inlineOverrides)
        result = applyGracePeriod(result, mr, aiConfig)
        result = policies.applyGraceWindow(result, mr, aiConfig.gracePeriod)
        result = applyConditionalSections(result, mr, aiConfig)
        result = applyRuleComposition(result, mr, aiConfig)
        result = applyAuthorSeniority(result, mr, aiConfig)
        result = applyTeamScoping(result, mr, aiConfig)
        result = applyTrackOverrides(result, mr, aiConfig)
        result = applyAdrValidation(result, mr, aiConfig)

        return result
    }

    private fun expandMacros(
        findings: List<Finding>,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        aiConfig.macroReferences
            .map { it.useMacro }
            .filter { it !in aiConfig.macroDefinitions }
            .distinct()
            .forEach { logger.warn("Rule macros: macroReferences uses undefined macro '$it'; the reference is ignored") }
        if (aiConfig.macroDefinitions.isEmpty() || aiConfig.macroReferences.isEmpty()) return findings
        val expanded = RuleMacroExpander.expand(aiConfig.macroDefinitions, aiConfig.macroReferences)
        if (expanded.isEmpty()) return findings

        logger.info("Rule macros: ${expanded.size} macro(s) expanded")
        return findings.mapNotNull { finding ->
            // Findings without a file match only macros applied to every path (`**`, as `applyMacros` does).
            val path = (finding.location as? Location.FileRef)?.path.orEmpty()
            applyRuleOverride(finding, ruleSetting(RuleMacroExpander.findApplicableRules(path, expanded), finding.rule))
        }
    }

    /**
     * Applies a macro/team/track rule override to [finding]: a severity string, or an object with
     * `severity` (or `enforceSeverity`) and/or `enabled`. `enabled: false` (or the string `skip`) drops the finding.
     */
    private fun applyRuleOverride(
        finding: Finding,
        override: JsonElement?,
    ): Finding? {
        val severity = when (override) {
            is JsonPrimitive -> override.contentOrNull
            is JsonObject -> ((override["severity"] ?: override["enforceSeverity"]) as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        val enabled = ((override as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull
        return when {
            enabled == false || severity.equals(SKIP, ignoreCase = true) -> null
            severity != null -> finding.copy(severity = Severity.fromString(severity, default = finding.severity))
            else -> finding
        }
    }

    private fun applyProgressiveEnforcement(
        findings: List<Finding>,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        val schedule = aiConfig.enforcementSchedule
        if (schedule.isEmpty()) return findings

        val currentDate = now()
            .toLocalDateTime(TimeZone.UTC)
            .date
            .toString()
        val phase = ProgressiveEnforcement.resolvePhase(schedule, currentDate)
        if (phase == null || phase.mode == EnforcementMode.ENFORCE) return findings

        val block = ProgressiveEnforcement.blockSeverities(phase)
        logger.info("Progressive enforcement: mode=${phase.mode} applied")
        return findings.map { finding ->
            finding.copy(severity = ProgressiveEnforcement.adjustSeverity(finding.severity, phase.mode, block))
        }
    }

    private fun applySeverityReclassification(
        findings: List<Finding>,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        val severityOverrides = aiConfig.severityOverrides
        if (severityOverrides.isEmpty()) return findings

        val reclassResult = SeverityReclassifier.apply(findings, severityOverrides, logger)
        if (reclassResult.reclassifiedCount > 0) {
            logger.info("Severity reclassification: ${reclassResult.reclassifiedCount} finding(s) reclassified")
        }
        return reclassResult.findings
    }

    private fun applyViolationBudgets(
        findings: List<Finding>,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        if (aiConfig.violationBudgets.isEmpty()) return findings

        val currentDate = now()
            .toLocalDateTime(TimeZone.UTC)
            .date
            .toString()
        val budgetResult = ViolationBudgetEvaluator.evaluate(findings, aiConfig.violationBudgets, currentDate)
        budgetResult.overBudget.forEach { (rule, counts) ->
            logger.info("Violation budget: rule $rule OVER BUDGET (${counts.first} violations, allowance ${counts.second})")
        }
        return budgetResult.findings
    }

    private fun applyGracePeriod(
        findings: List<Finding>,
        mr: MergeRequest,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        val activeGrace = GracePeriodEvaluator.findActiveGracePeriod(mr, aiConfig.gracePeriods) { logger.warn(it) }
            ?: return findings
        if (!admitted(activeGrace, mr)) return findings
        val active = "Grace period active: ${activeGrace.name}"
        if (activeGrace.audit) logger.info(active) else logger.debug(active)
        return findings.mapNotNull { finding ->
            val ruleOverride = activeGrace.overrideRules[finding.rule]
            when {
                ruleOverride == null && activeGrace.overrideRules.isNotEmpty() -> {
                    finding
                }

                ruleOverride.equals(SKIP, ignoreCase = true) -> {
                    null
                }

                ruleOverride != null -> {
                    finding.copy(severity = Severity.fromString(ruleOverride, default = finding.severity))
                }

                else -> {
                    finding.copy(severity = downgrade(finding.severity))
                }
            }
        }
    }

    /** records the MR's use of [grace] and enforces `maxDuration` and `maxUsesPerDay` from the AI state. */
    private fun admitted(
        grace: GracePeriodConfig,
        mr: MergeRequest,
    ): Boolean {
        val store = persistence
        if (store == null) {
            val limited = grace.maxDuration != null || grace.maxUsesPerDay != null
            if (limited && warnedNoGraceState.compareAndSet(expect = false, update = true)) {
                logger.warn("Grace period '${grace.name}': no AI state persistence, maxDuration and maxUsesPerDay cannot be enforced")
            }
            return true
        }
        val admission = GracePeriodEvaluator.admit(grace, MrCacheKey.buildKey(mr), now(), store.loadGraceUsage())
        store.saveGraceUsage(admission.state)
        admission.blockedReason?.let { logger.warn("Grace period '${grace.name}' not applied: $it") }
        return admission.blockedReason == null
    }

    private fun downgrade(severity: Severity): Severity =
        when (severity) {
            Severity.ERROR -> Severity.WARNING
            else -> Severity.INFO
        }

    private fun applyConditionalSections(
        findings: List<Finding>,
        mr: MergeRequest,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        val conditionalSections = aiConfig.conditionalSections
        if (conditionalSections.isEmpty()) return findings

        val sectionFindings = ConditionalSectionChecker.check(mr, conditionalSections)
        if (sectionFindings.isEmpty()) return findings

        logger.info("Conditional sections: ${sectionFindings.size} missing section finding(s)")
        return findings + sectionFindings
    }

    private fun applyRuleComposition(
        findings: List<Finding>,
        mr: MergeRequest,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        val compositions = aiConfig.compositions
        if (compositions.isEmpty()) return findings

        val composedFindings = RuleComposer.evaluate(findings, compositions, mr)
        if (composedFindings.isEmpty()) return findings

        logger.info("Rule composition: ${composedFindings.size} composed finding(s)")
        return findings + composedFindings
    }

    private fun applyAuthorSeniority(
        findings: List<Finding>,
        mr: MergeRequest,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        val seniorityTiers = aiConfig.seniorityTiers
        if (seniorityTiers.isEmpty()) return findings

        val tier = AuthorSeniorityResolver.resolve(mr, seniorityTiers) ?: return findings
        return findings.map { finding ->
            finding.copy(severity = AuthorSeniorityResolver.adjustSeverity(finding.severity, tier, finding.rule))
        }
    }

    private fun applyTeamScoping(
        findings: List<Finding>,
        mr: MergeRequest,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        if (aiConfig.teams.isEmpty()) return findings

        val affected = TeamNamespaceResolver.affectedTeams(mr.changes, aiConfig.teams)
        if (affected.isNotEmpty()) {
            logger.info("Team scoping: affected teams: ${affected.joinToString(", ")}")
        }
        val ownership = TeamNamespaceResolver.resolveOwnership(mr.changes, aiConfig.teams)
        val teamsByName = aiConfig.teams.associateBy { it.name }
        return findings.mapNotNull { finding ->
            val path = (finding.location as? Location.FileRef)?.path ?: return@mapNotNull finding
            ownership.entries
                .filter { (_, owned) -> owned.any { it.path == path } }
                .fold<Map.Entry<String, List<FileChange>>, Finding?>(finding) { current, (team, _) ->
                    current?.let { applyRuleOverride(it, teamsByName[team]?.ruleOverrides?.let { o -> ruleSetting(o, it.rule) }) }
                }
        }
    }

    private fun applyTrackOverrides(
        findings: List<Finding>,
        mr: MergeRequest,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        val track = EnvironmentTrackResolver.resolve(mr.targetBranch, aiConfig.tracks) ?: return findings
        val withApprovals = findings + listOfNotNull(EnvironmentTrackResolver.approvalsFinding(track, mr))
        if (track.ruleOverrides.isEmpty()) return withApprovals
        logger.info("Environment track '${track.name}': rule settings applied for target ${mr.targetBranch}")
        return withApprovals.mapNotNull { applyRuleOverride(it, ruleSetting(track.ruleOverrides, it.rule)) }
    }

    private fun applyAdrValidation(
        findings: List<Finding>,
        mr: MergeRequest,
        aiConfig: AiPluginConfig,
    ): List<Finding> {
        if (aiConfig.adrs.isEmpty()) return findings

        val adrFindings = AdrValidator.validate(mr, aiConfig.adrs)
        if (adrFindings.isEmpty()) return findings

        logger.info("ADR validation: ${adrFindings.size} violation(s)")
        return findings + adrFindings
    }

    companion object {
        private const val SKIP = "skip"

        /** Rule-keyed config maps may arrive camelCased by key normalization, so both spellings are tried. */
        private fun ruleSetting(
            settings: Map<String, JsonElement>,
            ruleId: String,
        ): JsonElement? = settings[ruleId] ?: settings[ConfigKeyNormalizer.kebabToCamelCase(ruleId)]
    }
}
