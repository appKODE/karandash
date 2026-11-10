package io.karandash.api.ai

/**
 * Abstraction over token consumption tracking for AI completion calls.
 *
 * Implementations enforce per-run or per-rule budgets to prevent runaway costs.
 * The built-in [io.karandash.plugins.ai.core.TokenBudget] class implements this interface;
 * third-party plugins can supply alternative strategies (e.g. cost-based, rate-limited).
 */
public interface AiTokenBudget {
    /** Whether [estimatedTokens] can be spent without exceeding the budget. */
    public fun canSpend(estimatedTokens: Int): Boolean

    /** Record actual token consumption after a completion call returns. */
    public fun recordSpend(tokens: Int)

    /** Tokens remaining before the budget is exhausted. */
    public fun remaining(): Int

    /** Whether zero budget remains. */
    public fun isExhausted(): Boolean
}
