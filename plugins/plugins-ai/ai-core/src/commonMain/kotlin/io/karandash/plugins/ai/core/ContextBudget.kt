package io.karandash.plugins.ai.core

import io.karandash.plugins.ai.core.config.AiBudgetConfig
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * `ai.context.budget`: the input tokens one AI request may use, the single knob the prompt tiers are fit into.
 * `auto` (the default) takes [AUTO_WINDOW_SHARE] of the model's context window, at most [AUTO_MAX_TOKENS], and
 * no more than the per-run cost cap (`ai.costBudget.maxCostPerRunUsd`) buys at the model's input price.
 * An explicit `ai.budget.max-input-tokens` stays a ceiling over it.
 */
object ContextBudget {
    const val AUTO = "auto"

    /** The auto budget never exceeds this many tokens, however large the window. */
    const val AUTO_MAX_TOKENS = 64_000

    /** The auto budget is the context window divided by this. */
    const val AUTO_WINDOW_SHARE = 4

    /** Context window for a model the table does not know. */
    const val DEFAULT_CONTEXT_WINDOW = 128_000

    private const val TOKENS_PER_PRICING_UNIT = 1_000.0

    /** Context windows by model-name prefix (after any `provider/`), longest prefix wins. */
    private val CONTEXT_WINDOWS: Map<String, Int> = mapOf(
        "claude" to 200_000,
        "gpt-4o" to 128_000,
        "gpt-4.1" to 1_000_000,
        "gpt-5" to 400_000,
        "o3" to 200_000,
        "o4" to 200_000,
        "gemini" to 1_000_000,
        "kimi" to 256_000,
        "qwen" to 128_000,
        "deepseek" to 128_000,
        "llama" to 128_000,
    )

    /**
     * The explicit token count in `ai.context.budget`, or null for `auto` or no setting. Anything else (not a
     * positive integer) is reported through [logger] and read as `auto`.
     */
    fun parse(
        raw: JsonElement?,
        logger: Logger? = null,
    ): Int? {
        val primitive = raw as? JsonPrimitive ?: return null.also { if (raw != null) warnInvalid(raw, logger) }
        if (primitive.content.equals(AUTO, ignoreCase = true)) return null
        return primitive.intOrNull?.takeIf { it > 0 } ?: primitive.content
            .trim()
            .toIntOrNull()
            ?.takeIf { it > 0 }
            ?: null.also { warnInvalid(raw, logger) }
    }

    private fun warnInvalid(
        raw: JsonElement,
        logger: Logger?,
    ) {
        logger?.warn("ai.context.budget: '$raw' is not '$AUTO' or a positive token count; using '$AUTO'")
    }

    /** [model]'s context window in tokens, [DEFAULT_CONTEXT_WINDOW] when unknown. */
    fun contextWindow(model: String): Int {
        val name = model.substringAfterLast('/').lowercase()
        return CONTEXT_WINDOWS.entries
            .filter { name.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
            ?: DEFAULT_CONTEXT_WINDOW
    }

    /** Input tokens [maxCostPerRunUsd] buys at [inputPricePer1K] USD; null when either is not positive. */
    fun costCapTokens(
        maxCostPerRunUsd: Double,
        inputPricePer1K: Double?,
    ): Int? {
        if (maxCostPerRunUsd <= 0.0 || inputPricePer1K == null || inputPricePer1K <= 0.0) return null
        return (maxCostPerRunUsd / inputPricePer1K * TOKENS_PER_PRICING_UNIT).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    /** The `auto` budget for [model]: a share of its window, at most [AUTO_MAX_TOKENS] and [costCapTokens]. */
    fun auto(
        model: String,
        costCapTokens: Int? = null,
    ): Int = minOf(contextWindow(model) / AUTO_WINDOW_SHARE, AUTO_MAX_TOKENS, costCapTokens ?: Int.MAX_VALUE).coerceAtLeast(1)

    /**
     * The input tokens one request to [model] may use: [setting] (`ai.context.budget`) when given, else
     * `ai.budget.max-input-tokens` when set, else [auto]; an explicit `max-input-tokens` caps an explicit [setting].
     */
    fun resolve(
        setting: Int?,
        budget: AiBudgetConfig,
        model: String,
        costCapTokens: Int? = null,
    ): Int {
        val ceiling = budget.maxInputTokens
        return when {
            setting != null -> minOf(setting, ceiling ?: setting)
            ceiling != null -> ceiling
            else -> auto(model, costCapTokens)
        }
    }
}
