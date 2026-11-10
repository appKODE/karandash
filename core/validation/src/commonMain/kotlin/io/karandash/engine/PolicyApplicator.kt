package io.karandash.engine

import io.karandash.api.ModuleRegistry
import io.karandash.api.context.ConditionContext
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.conditions.BuiltinPredicates
import io.karandash.conditions.MutablePredicateRegistry
import io.karandash.conditions.PolicyEngine
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.keys.PolicyKeys
import io.karandash.config.merge.JsonElementMerger
import io.karandash.logger.Logger
import io.karandash.models.ActivePolicy
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * Handles policy evaluation and application for [ValidationEngine].
 *
 * Extracted to keep [ValidationEngine] within the detekt LargeClass threshold.
 *
 * @param config The resolved config.
 * @param registry Module registry for predicate discovery.
 * @param policyEngine Engine for evaluating policy conditions.
 * @param policies List of policy configs to evaluate.
 * @param logger Logger for diagnostic messages.
 * @param envLookup Allowlisted environment variable lookup function.
 * @param ciPlatform Detected CI platform name (e.g., "gitlab", "github").
 * @param isCI Whether running in a CI environment.
 */
class PolicyApplicator(
    private val config: Config,
    private val registry: ModuleRegistry,
    private val policyEngine: PolicyEngine?,
    private val policies: List<PolicyConfig>,
    private val logger: Logger,
    private val envLookup: (String) -> String? = { null },
    private val ciPlatform: String? = null,
    private val isCI: Boolean = false,
    private val patternExpander: ((String) -> String)? = null,
) {
    /**
     * Evaluates policies against the MR and produces a [PolicyApplicationResult]
     * containing the runtime config (with merged overrides), active policies,
     * and per-rule enablement overrides.
     */
    suspend fun applyPolicies(mr: MergeRequest): PolicyApplicationResult {
        if (policyEngine == null || policies.isEmpty()) {
            return PolicyApplicationResult(config, emptyList(), emptyMap())
        }

        val context = buildConditionContext(mr, priorFindings = emptyList())
        val predicateRegistry = buildPredicateRegistry(priorFindings = null)
        val ruleKeys = config.rules.ruleConfigs.keys
        val activePolicies = policyEngine
            .evaluate(policies, context, predicateRegistry)
            .map { canonicalizeRuleIds(it, ruleKeys) }

        logPolicyEvaluationResults(activePolicies)

        val matchedPolicies = activePolicies.filter { it.matched }
        val skippedPolicyOverrides = collectSkippedPolicyOverrides(activePolicies)

        if (matchedPolicies.isEmpty()) {
            return PolicyApplicationResult(
                config,
                activePolicies,
                emptyMap(),
                skippedPolicyOverrides = skippedPolicyOverrides,
            )
        }

        var ruleConfigs = config.rules.ruleConfigs
        val severityMeta = mutableMapOf<String, RuleExecutionMeta>()
        val enabledOverrides = mutableMapOf<String, Boolean>()
        val moduleEnabledOverrides = mutableMapOf<String, Boolean>()
        var autoApproveOverride: Boolean? = null
        var autoFixOverride: Boolean? = null
        var scriptsOverride: Boolean? = null
        var dangerfileOverride: Boolean? = null

        for (policy in matchedPolicies) {
            val label = policy.displayLabel
            logger.debug("Applying policy '$label'")
            val affectedModuleIds = policy.affectedModules
            val enabled = policy.forcedEnabled

            if (affectedModuleIds != null && enabled != null) {
                for (moduleId in affectedModuleIds) {
                    logger.debug("  Policy '$label': module '$moduleId' enabled=$enabled")
                    moduleEnabledOverrides[moduleId] = enabled
                    enabledOverrides[moduleId] = enabled
                }
            } else if (affectedModuleIds == null && enabled != null) {
                logger.debug("  Policy '$label': all rules enabled=$enabled (${ruleConfigs.keys.size} rules)")
                for (ruleId in ruleConfigs.keys.toList()) {
                    enabledOverrides[ruleId] = enabled
                }
            }

            val overrides = policy.configOverrides
            if (overrides != null) {
                val overrideTargets = affectedModuleIds ?: overrides.keys.toList()
                logger.debug("  Policy '$label': config overrides applied to: $overrideTargets")
                ruleConfigs = mergeOverridesInto(ruleConfigs, overrideTargets, overrides)
            }

            policy.forcedSeverity?.let { severity ->
                val targets = affectedModuleIds ?: ruleConfigs.keys.toList()
                logger.debug("  Policy '$label': severity=$severity forced on: $targets")
                for (ruleId in targets) {
                    val meta = severityMeta[ruleId] ?: config.rules.metaFor(ruleId)
                    severityMeta[ruleId] = meta.copy(severity = severity)
                }
            }

            aggregateStageOverride(policy.stageOverrides[PolicyKeys.AUTO_APPROVE]) { autoApproveOverride = it }
            aggregateStageOverride(policy.stageOverrides[PolicyKeys.AUTO_FIX]) { autoFixOverride = it }
            aggregateStageOverride(policy.stageOverrides[PolicyKeys.SCRIPTS]) { scriptsOverride = it }
            aggregateStageOverride(policy.stageOverrides[PolicyKeys.DANGERFILE]) { dangerfileOverride = it }
        }

        val runtimeConfig = if (ruleConfigs != config.rules.ruleConfigs || severityMeta.isNotEmpty()) {
            config.copy(
                rules = config.rules.copy(ruleConfigs = ruleConfigs, ruleMeta = config.rules.ruleMeta + severityMeta),
            )
        } else {
            config
        }

        logMergedConfigDiff(runtimeConfig)

        return PolicyApplicationResult(
            runtimeConfig = runtimeConfig,
            activePolicies = activePolicies,
            enabledOverrides = enabledOverrides,
            moduleEnabledOverrides = moduleEnabledOverrides,
            autoApproveOverride = autoApproveOverride,
            autoFixOverride = autoFixOverride,
            scriptsOverride = scriptsOverride,
            dangerfileOverride = dangerfileOverride,
            skippedPolicyOverrides = skippedPolicyOverrides,
        )
    }

    /**
     * Filters rule IDs by their `when:` conditions and applies any inline
     * `when.override` blocks to the runtime config for matching rules.
     *
     * For each rule:
     * - No `when:` → included as-is.
     * - `when.expression` evaluates to `false` → excluded (skipped).
     * - `when.expression` fails to evaluate → excluded, with the error in [WhenFilterResult.whenErrors].
     * - `when.expression` evaluates to `true`, `when.override` is non-null →
     *   included; override deep-merged into that rule's config in the returned config.
     * - `when.expression` evaluates to `true`, no override → included as-is.
     *
     * @param priorFindings Findings of the previous pass, or null when there was none (pass 1): the
     *   `rule_*` result predicates exist only for a later pass, where an empty list means every rule passed.
     * @return [WhenFilterResult] with the active rule IDs and a (possibly updated)
     *   runtime config reflecting any inline `when.override` merges.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: condition expressions may throw on malformed input
    fun filterByWhen(
        runtimeConfig: Config,
        ruleIds: List<String>,
        mr: MergeRequest,
        priorFindings: List<Finding>?,
    ): WhenFilterResult {
        val context = buildConditionContext(mr, priorFindings.orEmpty())
        val predicateRegistry = buildPredicateRegistry(priorFindings)

        val evaluator = BuiltinPredicates.evaluator(
            context,
            predicateRegistry,
            patternExpander,
            config.limits.expressions.effectiveMaxLength,
            config.limits.expressions.effectiveMaxNestingDepth,
            config.limits.regex.effectiveMaxPatternLength,
        )

        val activeRuleIds = mutableListOf<String>()
        val whenErrors = mutableMapOf<String, String>()
        val overriddenRuleConfigs = mutableMapOf<String, JsonElement>()

        for (ruleId in ruleIds) {
            val whenConfig = runtimeConfig.rules.metaFor(ruleId).`when`
            if (whenConfig == null) {
                activeRuleIds.add(ruleId)
                continue
            }
            val conditionMet = try {
                evaluator.evaluate(whenConfig.expression)
            } catch (e: Exception) {
                // boundary: condition evaluation may throw on malformed expressions
                logger.warn("when evaluation failed for rule '$ruleId': ${e.message}")
                whenErrors[ruleId] = e.message ?: e::class.simpleName.orEmpty()
                false
            }
            if (!conditionMet) {
                logger.debug("Rule [$ruleId]: skipped (when: '${whenConfig.expression}')")
                continue
            }
            activeRuleIds.add(ruleId)
            val override = whenConfig.override ?: continue
            val base = runtimeConfig.rules.ruleConfigs[ruleId] as? JsonObject
                ?: JsonObject(emptyMap())
            val normalizedOverride = JsonObject(
                override.entries.associate { (k, v) ->
                    ConfigKeyNormalizer.kebabToCamelCase(k) to v
                },
            )
            val merged = JsonElementMerger.merge(base, normalizedOverride)
            overriddenRuleConfigs[ruleId] = merged
            logger.debug("Rule [$ruleId]: when condition met, inline override applied")
        }

        val effectiveConfig = if (overriddenRuleConfigs.isEmpty()) {
            runtimeConfig
        } else {
            val newRuleConfigs = runtimeConfig.rules.ruleConfigs + overriddenRuleConfigs
            runtimeConfig.copy(rules = runtimeConfig.rules.copy(ruleConfigs = newRuleConfigs))
        }

        return WhenFilterResult(activeRuleIds, effectiveConfig, whenErrors)
    }

    /**
     * Builds a [MutablePredicateRegistry] with common predicates, field-match
     * predicates, module-contributed predicates, and rule-result predicates when [priorFindings] is non-null.
     */
    fun buildPredicateRegistry(priorFindings: List<Finding>?): MutablePredicateRegistry<ConditionContext> =
        BuiltinPredicates.createRegistry(registry, priorFindings)

    private fun buildConditionContext(
        mr: MergeRequest,
        priorFindings: List<Finding>,
    ): ConditionContext =
        ConditionContext(
            mr = mr,
            priorFindings = priorFindings,
            env = envLookup,
            ciPlatform = ciPlatform,
            isCI = isCI,
            profileId = config.profile?.id,
            dryRun = config.dryRun,
            configuredModuleIds = collectConfiguredModuleIds(),
            nowEpochSeconds = Clock.System.now().epochSeconds,
        )

    private fun collectConfiguredModuleIds(): Set<String> {
        val ids = mutableSetOf<String>()
        ids.addAll(config.rules.ruleConfigs.keys)
        config.reporters?.keys?.let { ids.addAll(it) }
        config.platforms?.keys?.let { ids.addAll(it) }
        return ids
    }

    private fun logPolicyEvaluationResults(activePolicies: List<ActivePolicy>) {
        val matchedCount = activePolicies.count { it.matched }
        val nonMatchedCount = activePolicies.size - matchedCount
        logger.debug(
            "Policies: ${activePolicies.size} evaluated, $matchedCount matched, $nonMatchedCount not matched",
        )
        for (policy in activePolicies) {
            val label = policy.displayLabel
            val overrideTargets = policy.configOverrides?.keys?.toList() ?: emptyList()
            if (policy.matched) {
                logger.debug("  Policy '$label': MATCHED (when: ${policy.condition})")
                if (overrideTargets.isNotEmpty()) {
                    logger.debug("    Config overrides applied to: $overrideTargets")
                }
                if (policy.affectedModules != null) {
                    logger.debug("    Affected modules: ${policy.affectedModules}")
                }
            } else {
                logger.debug("  Policy '$label': NOT MATCHED (when: ${policy.condition})")
                if (overrideTargets.isNotEmpty()) {
                    logger.debug("    Would have overridden: $overrideTargets")
                }
            }
        }
    }

    private fun collectSkippedPolicyOverrides(activePolicies: List<ActivePolicy>,): List<SkippedRuleInfo> {
        val skipped = mutableListOf<SkippedRuleInfo>()
        for (policy in activePolicies) {
            if (policy.matched) continue
            val overrides = policy.configOverrides ?: continue
            val label = policy.displayLabel
            for (ruleId in overrides.keys) {
                skipped.add(
                    SkippedRuleInfo(
                        ruleId = ruleId,
                        reason = "policy '$label' override not applied (when: ${policy.condition})",
                        category = SkipCategory.POLICY_OVERRIDE_NOT_APPLIED,
                        policy = label,
                    ),
                )
            }
        }
        return skipped
    }

    private fun logMergedConfigDiff(runtimeConfig: Config) {
        if (runtimeConfig.rules.ruleConfigs == config.rules.ruleConfigs) {
            logger.debug("Runtime config unchanged (no policy overrides applied)")
            return
        }
        logger.debug("Runtime config differs from base (policies applied overrides)")
        for ((ruleId, ruleConfig) in runtimeConfig.rules.ruleConfigs) {
            val baseConfig = config.rules.ruleConfigs[ruleId]
            if (ruleConfig != baseConfig) {
                logger.debug("  Rule [$ruleId] config (merged): $ruleConfig")
                if (baseConfig != null) {
                    logger.debug("  Rule [$ruleId] config (base):   $baseConfig")
                }
            }
        }
    }

    companion object {
        /**
         * Rewrites the policy's `modules:` entries and `override:` keys to the configured rule key
         * they name ([ModuleIdNormalizer.resolve]), so `title-length`, `title_length` and
         * `TitleLength` all target `titleLength`. Ids matching no rule (reporters, platforms,
         * enrichment providers) are kept as written.
         */
        private fun canonicalizeRuleIds(
            policy: ActivePolicy,
            ruleKeys: Set<String>,
        ): ActivePolicy {
            fun canonical(id: String) = ModuleIdNormalizer.resolve(id, ruleKeys) ?: id
            return policy.copy(
                affectedModules = policy.affectedModules?.map(::canonical),
                configOverrides = policy.configOverrides?.let { overrides ->
                    JsonObject(overrides.mapKeys { (id, _) -> canonical(id) })
                },
            )
        }

        private fun mergeOverridesInto(
            ruleConfigs: Map<String, JsonElement>,
            affectedRuleIds: List<String>,
            overrides: JsonObject,
        ): Map<String, JsonElement> {
            val merged = ruleConfigs.toMutableMap()
            for (ruleId in affectedRuleIds) {
                val ruleOverride = overrides[ruleId] as? JsonObject ?: continue
                val base = merged[ruleId] as? JsonObject ?: JsonObject(emptyMap())
                merged[ruleId] = JsonElementMerger.merge(base, ruleOverride)
            }
            return merged
        }

        /**
         * Aggregates a stage override value using AND semantics: any `false` wins.
         * Only calls [setter] when the value is `false`.
         */
        private inline fun aggregateStageOverride(
            value: Boolean?,
            setter: (Boolean) -> Unit
        ) {
            if (value == false) setter(false)
        }
    }
}

/**
 * Internal result of policy application before validation begins.
 *
 * [moduleEnabledOverrides] tracks enablement for all module types (not just rules).
 * Stage override booleans gate pipeline stages: `false` means the stage is disabled.
 */
@Serializable
data class PolicyApplicationResult(
    val runtimeConfig: Config,
    val activePolicies: List<ActivePolicy>,
    val enabledOverrides: Map<String, Boolean>,
    val moduleEnabledOverrides: Map<String, Boolean> = emptyMap(),
    val autoApproveOverride: Boolean? = null,
    val autoFixOverride: Boolean? = null,
    val scriptsOverride: Boolean? = null,
    val dangerfileOverride: Boolean? = null,
    /** Rules whose config would have been overridden by non-matched policies. */
    val skippedPolicyOverrides: List<SkippedRuleInfo> = emptyList(),
)

/**
 * Result of [PolicyApplicator.filterByWhen].
 *
 * @property activeRuleIds Rule IDs that passed their `when:` condition (or had none).
 * @property runtimeConfig Config with any `when.override` blocks deep-merged in for
 *   matching rules. Equals the input config when no overrides were applied.
 * @property whenErrors Rule ID to error message for each rule whose `when:` expression failed to evaluate
 *   (excluded from [activeRuleIds], not the same as a false condition).
 */
@Serializable
data class WhenFilterResult(
    val activeRuleIds: List<String>,
    val runtimeConfig: Config,
    val whenErrors: Map<String, String> = emptyMap(),
)
