package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.plugins.ai.core.GateConfig
import io.karandash.plugins.ai.core.RuleGate
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.config.AiCostBudgetResolver
import io.karandash.plugins.ai.core.config.BudgetAction
import io.karandash.plugins.ai.core.config.CanaryConfig
import io.karandash.plugins.ai.core.config.CanaryPromotionEvaluator
import io.karandash.plugins.ai.core.config.CostBudgetConfig
import io.karandash.plugins.ai.core.config.EnvironmentTrackResolver
import io.karandash.plugins.ai.core.config.TrackConfig
import io.karandash.plugins.ai.core.rules.AiSkipPolicy
import io.karandash.utils.finding.FindingFactory
import kotlinx.serialization.Serializable

/**
 * Result of a skip-condition evaluation pass.
 *
 * The caller is responsible for updating shared atomic state when a [Skip] is returned.
 */
internal sealed class SkipResult {
    /** No skip condition matched - proceed with AI evaluation. */
    data object Continue : SkipResult()

    /**
     * A skip condition matched.
     *
     * @param reason Human-readable reason stored in skip-reason tracking state by the caller.
     * @param findings Findings to emit in place of real AI analysis (may be empty).
     * @param coverage Review coverage to record for the skipped rule, or null to record none.
     */
    @Serializable
    data class Skip(
        val reason: String,
        val findings: List<Finding>,
        val coverage: RuleCoverage? = null,
    ) : SkipResult()
}

/**
 * Evaluates skip/gate conditions for AI rules before dispatching to a provider.
 *
 * Does not update any shared atomic state - callers apply the [SkipResult.Skip.reason] to their
 * own state tracking after receiving the result.
 *
 * @param logger Logger used for debug/info/warn output.
 * @param ruleGate Gate evaluator (stateless, reused across rules).
 * @param canaryTracker Source of the metrics canary promotion is evaluated against.
 */
internal class AiSkipEvaluator(
    private val logger: Logger,
    private val ruleGate: RuleGate,
    val canaryTracker: CanaryTracker = CanaryTracker(persistence = null),
) {
    /**
     * Runs all skip checks in order: gates, cost budget, environment track, canary, dry-run,
     * trivial diff and size policy. The token budget is
     * gated once, in [AiRuleDispatcher], so a budget skip is recorded in the coverage ledger.
     *
     * @return [SkipResult.Skip] with findings to return immediately if the rule should be
     *   skipped, or [SkipResult.Continue] to proceed with AI evaluation.
     */
    fun evaluateSkipConditions(
        ruleId: String,
        mr: MergeRequest,
        input: SkipEvalInput,
        budget: TokenBudget,
    ): SkipResult {
        if (input.profileTimedOut) {
            // an analyzer already running when it passes is stopped by AiRuleModule.validate
            logger.warn("AI plugin: skipped $ruleId: the execution profile totalTimeout has passed")
            return SkipResult.Skip(reason = PROFILE_TIMEOUT_REASON, findings = emptyList())
        }
        evaluateGates(ruleId, mr, input.gates)
            .let { if (it is SkipResult.Skip) return it }

        evaluateCostBudget(ruleId, mr, input.costBudget, budget, input.spentUsd)
            .let { if (it is SkipResult.Skip) return it }

        evaluateEnvironmentTrack(ruleId, mr.targetBranch, input.tracks)
            .let { if (it is SkipResult.Skip) return it }

        evaluateCanary(ruleId, input.canary, mr.id)
            .let { if (it is SkipResult.Skip) return it }

        checkTrivialDiff(ruleId, mr, input)
            .let { if (it is SkipResult.Skip) return it }

        checkSizeSkipPolicy(ruleId, mr, input.skipPolicy)
            .let { if (it is SkipResult.Skip) return it }

        return SkipResult.Continue
    }

    /** Evaluates rule gates. Returns [SkipResult.Skip] with empty findings if gated, else [SkipResult.Continue]. */
    fun evaluateGates(
        ruleId: String,
        mr: MergeRequest,
        gates: List<GateConfig>,
    ): SkipResult {
        val gateSkipReason = ruleGate.evaluate(ruleId, mr, gates) { logger.warn("AI plugin: $it") } ?: return SkipResult.Continue
        logger.info("AI plugin [$ruleId]: gated — $gateSkipReason")
        return SkipResult.Skip(reason = "gated: $gateSkipReason", findings = emptyList())
    }

    /** Evaluates branch cost budget. Returns [SkipResult.Skip] if budget exceeded and action is SKIP, else [SkipResult.Continue]. */
    fun evaluateCostBudget(
        ruleId: String,
        mr: MergeRequest,
        costBudget: CostBudgetConfig?,
        budget: TokenBudget,
        spentUsd: Double = 0.0,
    ): SkipResult {
        if (costBudget == null) return SkipResult.Continue
        val branchTokenBudget = AiCostBudgetResolver.resolveTokenBudget(mr.sourceBranch, costBudget)
        logger.debug(
            "AI cost-budget [$ruleId]: consumed=${budget.totalConsumed()}" +
                ", remaining=${budget.remaining()}, branchLimit=$branchTokenBudget" +
                ", globalLimit=${costBudget.maxTokensPerRun}, onExceeded=${costBudget.onBudgetExceeded}" +
                ", branch=${mr.sourceBranch}",
        )
        val overUsd = AiCostBudgetResolver.isOverBudget(spentUsd, costBudget)
        if (overUsd || budget.totalConsumed() >= branchTokenBudget) {
            val action = AiCostBudgetResolver.onBudgetExceeded(costBudget) { logger.warn(it) }
            if (action == BudgetAction.SKIP_REMAINING) {
                val reason = if (overUsd) {
                    "skipped: budget — cost-budget.max-cost-per-run-usd cap of ${costBudget.maxCostPerRunUsd} USD reached ($spentUsd spent)"
                } else {
                    val cap = if (branchTokenBudget ==
                        costBudget.maxTokensPerRun
                    ) {
                        "cost-budget.max-tokens-per-run"
                    } else {
                        "cost-budget branch"
                    }
                    "skipped: budget — $cap cap of $branchTokenBudget tokens reached (${budget.totalConsumed()} used)"
                }
                logger.info("AI plugin [$ruleId]: $reason")
                return SkipResult.Skip(reason = reason, findings = emptyList())
            }
            logger.warn(
                "AI plugin [$ruleId]: branch cost budget exhausted ($branchTokenBudget tokens); continuing (onBudgetExceeded: warn)"
            )
        }
        return SkipResult.Continue
    }

    /**
     * Evaluates environment track configuration. Returns [SkipResult.Skip] if AI is disabled for the track or the
     * track's `enableRules`/`disableRules` leave [ruleId] out, else [SkipResult.Continue].
     */
    fun evaluateEnvironmentTrack(
        ruleId: String,
        targetBranch: String,
        tracks: List<TrackConfig>,
    ): SkipResult {
        if (tracks.isEmpty()) return SkipResult.Continue
        val track = EnvironmentTrackResolver.resolve(targetBranch, tracks) ?: return SkipResult.Continue
        if (track.aiEnabled && ruleId in EnvironmentTrackResolver.filterRules(track, setOf(ruleId))) return SkipResult.Continue
        logger.info("AI plugin [$ruleId]: disabled by environment track '${track.name}'")
        return SkipResult.Skip(reason = "disabled by track: ${track.name}", findings = emptyList())
    }

    /** Evaluates canary promotion stage. Returns [SkipResult.Skip] if rule is in shadow mode, else [SkipResult.Continue]. */
    fun evaluateCanary(
        ruleId: String,
        canary: Map<String, CanaryConfig>,
        mrId: String = "",
    ): SkipResult {
        val canaryConfig = canary[ruleId] ?: canary[ConfigKeyNormalizer.kebabToCamelCase(ruleId)] ?: return SkipResult.Continue
        // the stage is judged with this MR counted, but only an MR the rule is evaluated on is recorded as a sample
        val stage = CanaryPromotionEvaluator.evaluateStage(canaryConfig, canaryTracker.metricsWith(ruleId, canaryConfig, mrId))
        if (!CanaryPromotionEvaluator.shouldReport(stage)) {
            canaryTracker.recordSample(ruleId, canaryConfig, mrId)
            logger.info("AI plugin [$ruleId]: canary shadow mode — running silently")
            return SkipResult.Skip(reason = "canary: shadow mode", findings = emptyList())
        }
        if (CanaryPromotionEvaluator.inRollout(canaryConfig, stage, ruleId, mrId)) {
            canaryTracker.recordSample(ruleId, canaryConfig, mrId)
            return SkipResult.Continue
        }
        logger.info("AI plugin [$ruleId]: canary — MR outside the ${canaryConfig.percentOfMrs}% rollout")
        return SkipResult.Skip(reason = "canary: MR not in ${canaryConfig.percentOfMrs}% rollout", findings = emptyList())
    }

    /** Skips an MR whose every file is trivial ([TrivialDiff]), recording zero coverage over its files. */
    fun checkTrivialDiff(
        ruleId: String,
        mr: MergeRequest,
        input: SkipEvalInput,
    ): SkipResult {
        if (!input.skipPolicy.trivialDiffs) return SkipResult.Continue
        val reason = TrivialDiff.reason(mr.changes, input.excludePatterns) ?: return SkipResult.Continue
        logger.info("AI plugin [$ruleId]: skipping — $reason")
        val files = mr.changes.map { it.path }.toSet()
        return SkipResult.Skip(reason, emptyList(), RuleCoverage(files = files, reviewed = emptySet(), selfSkipReason = reason))
    }

    /**
     * Checks whether the MR exceeds any size-policy skip threshold.
     *
     * @return [SkipResult.Skip] with an info finding if a threshold is exceeded, else [SkipResult.Continue].
     */
    fun checkSizeSkipPolicy(
        ruleId: String,
        mr: MergeRequest,
        policy: AiSkipPolicy,
    ): SkipResult {
        if (policy.isUnconfigured()) return SkipResult.Continue
        val totalLines = mr.changes.sumOf { it.additions + it.deletions }
        logger.debug(
            "AI size-policy [skip]: files=${mr.changes.size}" +
                " (max=${policy.maxFiles ?: "∞"}), lines=$totalLines" +
                " (max=${policy.maxTotalLines ?: "∞"}), commits=${mr.commits.size}" +
                " (max=${policy.maxCommits ?: "∞"})",
        )
        val reasons = buildList {
            policy.maxFiles?.let { max ->
                if (mr.changes.size > max) add("${mr.changes.size} files exceeds maxFiles=$max")
            }
            policy.maxTotalLines?.let { max ->
                if (totalLines > max) add("$totalLines changed lines exceeds maxTotalLines=$max")
            }
            policy.maxCommits?.let { max ->
                if (mr.commits.size > max) add("${mr.commits.size} commits exceeds maxCommits=$max")
            }
        }
        if (reasons.isEmpty()) return SkipResult.Continue
        val reason = reasons.joinToString("; ")
        logger.info("AI plugin [$ruleId]: skipping — $reason")
        return SkipResult.Skip(
            reason = reason,
            findings = listOf(FindingFactory.info(rule = ruleId, message = "[SIZE POLICY] AI analysis skipped: $reason")),
        )
    }

    companion object {
        const val PROFILE_TIMEOUT_REASON = "execution profile totalTimeout reached"
    }
}

/**
 * Input value type carrying the subset of initialized state that [AiSkipEvaluator] needs.
 *
 * Populated by [io.karandash.plugins.ai.AiRuleModule] from its private `InitState`.
 */
@Serializable
internal data class SkipEvalInput(
    val gates: List<GateConfig>,
    val costBudget: CostBudgetConfig?,
    val tracks: List<TrackConfig>,
    val canary: Map<String, CanaryConfig>,
    val skipPolicy: AiSkipPolicy,
    val excludePatterns: List<String> = emptyList(),
    /** the active execution profile's `totalTimeout` has passed since the plugin started. */
    val profileTimedOut: Boolean = false,
    /** estimated USD the run has spent so far, checked against `costBudget.maxCostPerRunUsd`. */
    val spentUsd: Double = 0.0,
)
