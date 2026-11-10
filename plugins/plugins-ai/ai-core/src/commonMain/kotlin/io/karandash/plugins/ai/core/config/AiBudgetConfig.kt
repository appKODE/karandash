package io.karandash.plugins.ai.core.config

import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.serialization.Serializable

/**
 * `ai.budget`: [maxInputTokens], when set, caps one rule call's prompt (a ceiling over `ai.context.budget`, which
 * otherwise sizes it; see [io.karandash.plugins.ai.core.ContextBudget]), less [reserveOutputTokens] for a model whose
 * limit counts the reply too. T0 (system prompt, output contract, project instructions, conventions, MR and ticket
 * text, prior findings) is never cut, the diff takes what T0 leaves, and T2 context fills the rest by priority;
 * [weights] overrides a T2 part's priority by name (`crossFileContext`, `dependencyContext`,
 * `enricherContext`, or an enricher id). A rule whose T0 alone is over the cap is skipped and the item named.
 * The diff caps `ai.limits.maxDiffCharsTotal` / `maxDiffCharsPerFile` default to [promptCapTokens] in characters
 * when [maxInputTokens] is set.
 */
@Serializable
data class AiBudgetConfig(
    val maxInputTokens: Int? = null,
    val reserveOutputTokens: Int = 0,
    val weights: Map<String, Int> = emptyMap(),
) {
    /** The tokens one prompt may use: [maxInputTokens] (else [DEFAULT_MAX_INPUT_TOKENS]) less [reserveOutputTokens], never below zero. */
    val promptCapTokens: Int get() = ((maxInputTokens ?: DEFAULT_MAX_INPUT_TOKENS) - reserveOutputTokens).coerceAtLeast(0)

    /** The reply room every call plans and holds for: [reserveOutputTokens] when set, else [DEFAULT_RESERVE_OUTPUT_TOKENS]. */
    val outputReserveTokens: Int get() = reserveOutputTokens.takeIf { it > 0 } ?: DEFAULT_RESERVE_OUTPUT_TOKENS

    /** [weights] entry for the part [name] (keys arrive camelCased, enricher ids are kebab-case), else [default]. */
    fun priorityOf(
        name: String,
        default: Int,
    ): Int = weights[name] ?: weights[ConfigKeyNormalizer.kebabToCamelCase(name)] ?: default

    companion object {
        /** The cap [promptCapTokens] assumes when [maxInputTokens] is unset; the request budget itself is `ai.context.budget`. */
        const val DEFAULT_MAX_INPUT_TOKENS: Int = 32_000

        /** Reply room a call holds while `reserve-output-tokens` is unset (or 0): output is metered against the budget too. */
        const val DEFAULT_RESERVE_OUTPUT_TOKENS: Int = 2_000
        // limit: one reserve for every rule; analyzers set their output max-tokens inside (AnalyzerLimits), read
        // those per rule if holds still overdraw.
    }
}
