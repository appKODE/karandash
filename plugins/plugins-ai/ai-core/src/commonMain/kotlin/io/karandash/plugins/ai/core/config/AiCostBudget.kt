package io.karandash.plugins.ai.core.config

import io.karandash.helpers.RegexHelper
import kotlinx.serialization.Serializable

/**
 * AI cost budget with branch-aware scaling.
 * Hard dollar/token caps per run. Different branches get different budgets.
 */
object AiCostBudgetResolver {
    private const val ACTION_SKIP_REMAINING = "skip-remaining"
    private const val ACTION_WARN = "warn"

    /**
     * Resolves the effective token budget for a given branch.
     *
     * Branch budgets are evaluated in order; the first matching pattern wins.
     * Falls back to [CostBudgetConfig.maxTokensPerRun] if no branch pattern matches.
     *
     * @param sourceBranch The MR source branch name.
     * @param config The cost budget configuration.
     * @return The effective max token count for this run.
     */
    fun resolveTokenBudget(
        sourceBranch: String,
        config: CostBudgetConfig
    ): Int {
        for (branchBudget in config.branchBudgets) {
            val matches = sourceBranch.contains(branchBudget.pattern) ||
                RegexHelper.compileOrNull(branchBudget.pattern)?.containsMatchIn(sourceBranch) == true
            if (matches) return branchBudget.maxTokensPerRun
        }
        return config.maxTokensPerRun
    }

    /**
     * Checks if a cost has exceeded the per-run dollar budget.
     *
     * Returns `false` when [CostBudgetConfig.maxCostPerRunUsd] is zero or negative,
     * meaning no dollar cap is enforced.
     *
     * @param currentCostUsd The accumulated cost in USD for this run.
     * @param config The cost budget configuration.
     * @return `true` if the dollar cap has been reached or exceeded.
     */
    fun isOverBudget(
        currentCostUsd: Double,
        config: CostBudgetConfig
    ): Boolean = config.maxCostPerRunUsd > 0 && currentCostUsd >= config.maxCostPerRunUsd

    /**
     * Determines the action to take when the budget is exceeded.
     *
     * The value is matched case-insensitively; an unknown one falls back to `skip-remaining` and is reported to [warn].
     *
     * @param config The cost budget configuration.
     * @param warn Receives the message for an unknown value.
     * @return The [BudgetAction] to apply.
     */
    fun onBudgetExceeded(
        config: CostBudgetConfig,
        warn: (String) -> Unit = {},
    ): BudgetAction =
        when (config.onBudgetExceeded.trim().lowercase()) {
            ACTION_SKIP_REMAINING -> {
                BudgetAction.SKIP_REMAINING
            }

            ACTION_WARN -> {
                BudgetAction.WARN
            }

            else -> {
                warn(
                    "cost-budget.on-budget-exceeded '${config.onBudgetExceeded}' is not one of " +
                        "$ACTION_SKIP_REMAINING, $ACTION_WARN; using $ACTION_SKIP_REMAINING",
                )
                BudgetAction.SKIP_REMAINING
            }
        }
}

/** Cost and token budget configuration for AI rule evaluation. */
@Serializable
data class CostBudgetConfig(
    /** Maximum tokens to spend across all AI calls in a single validation run. */
    val maxTokensPerRun: Int = 50000,
    /**
     * Maximum cost in USD for a single validation run.
     * Set to 0.0 to disable the dollar cap.
     *
     * Enforced before each rule runs: once the run has spent this much, the budget action applies
     * (skip the remaining rules or warn), like [maxTokensPerRun].
     */
    val maxCostPerRunUsd: Double = 0.0,
    /**
     * Action taken when the budget is exceeded.
     * Accepted values: `skip-remaining` (default), `warn`.
     */
    val onBudgetExceeded: String = "skip-remaining",
    /** Per-branch budget overrides evaluated in order; first match wins. */
    val branchBudgets: List<BranchBudget> = emptyList(),
    /**
     * Splits the token budget across rules by their historical finding rate (from `ai.statistics`)
     * instead of evenly; falls back to an even split until enough runs are recorded.
     */
    val adaptiveAllocation: Boolean = false,
    /** End-of-run cost estimate: whether to print it and when to warn about it. */
    val costEstimate: CostEstimateConfig? = null,
)

/** `ai.costBudget.costEstimate`: the end-of-run cost summary and its alert. */
@Serializable
data class CostEstimateConfig(
    /** `false` omits the end-of-run cost summary. */
    val enabled: Boolean = true,
    /** Warns when the run's estimated cost exceeds [alertThreshold]. */
    val sendAlert: Boolean = true,
    /** Estimated run cost in USD above which the alert fires; 0 or less disables it. */
    val alertThreshold: Double = 0.0,
)

/** A branch-specific token budget override. */
@Serializable
data class BranchBudget(
    /** Substring or regex pattern matched against the source branch name. */
    val pattern: String,
    /** Maximum tokens for runs on branches matching this pattern. */
    val maxTokensPerRun: Int = 50000,
)

/** Action to take when the AI cost budget is exceeded during a run. */
enum class BudgetAction {
    /** Skip all remaining AI calls for this run. */
    SKIP_REMAINING,

    /** Emit a warning but continue processing. */
    WARN,
}
