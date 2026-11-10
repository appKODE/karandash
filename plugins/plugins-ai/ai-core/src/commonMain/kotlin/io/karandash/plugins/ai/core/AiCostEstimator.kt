package io.karandash.plugins.ai.core

import kotlinx.serialization.Serializable

/**
 * Estimates API cost based on token usage and model pricing.
 *
 * Prices are approximate and may not reflect current provider pricing.
 * Returns `null` when pricing data is unavailable for a given model.
 *
 * @param customPricing Additional or overriding model pricing entries applied on top of
 *   [DEFAULT_PRICING]. Each value is a (inputPricePer1kTokens, outputPricePer1kTokens) pair in USD.
 *   Entries here take precedence when there is a key collision.
 * @param promptCacheTtl `ai.promptCacheTtl`: `1h` prices Anthropic cache writes at the 1-hour rate, anything else at
 *   the 5-minute rate.
 */
class AiCostEstimator(
    customPricing: Map<String, Pair<Double, Double>> = emptyMap(),
    promptCacheTtl: String = PROMPT_CACHE_TTL_5M,
) {
    private val cacheWriteMultiplier = if (promptCacheTtl == PROMPT_CACHE_TTL_1H) CACHE_WRITE_1H_MULTIPLIER else CACHE_WRITE_5M_MULTIPLIER

    private val modelPricing: Map<String, Pair<Double, Double>> = DEFAULT_PRICING + customPricing

    /**
     * Strips routing and provider prefixes so bare model names can be matched against the pricing table.
     *
     * Handles any `provider/model` pattern by taking the segment after the last `/`:
     * - `openrouter/moonshotai/kimi-k2.5` → `kimi-k2.5`
     * - `moonshotai/kimi-k2.5-0127` → `kimi-k2.5-0127`
     * - `qwen/qwen3.5-35b-a3b-20260224` → `qwen3.5-35b-a3b-20260224`
     * - `gpt-4o` → `gpt-4o` (unchanged)
     * - `anthropic/claude-haiku-5.5` → `claude-haiku-5-5` (OpenRouter dots a Claude version, Anthropic dashes it)
     */
    private fun normalizeModel(model: String): String {
        val bare = model.substringAfterLast('/')
        return if (bare.startsWith(ANTHROPIC_PREFIX)) bare.replace('.', '-') else bare
    }

    /**
     * Pricing row for [model]: an exact match, else the longest table key that prefixes it
     * (so `gpt-4o-mini-2024-07-18` gets `gpt-4o-mini`, not `gpt-4o`).
     */
    fun pricingFor(model: String): Pair<Double, Double>? = modelPricing.longestPrefixMatch(normalizeModel(model))

    /** Price of a cached input token relative to a plain one for [model]; 1.0 when the model has no cache discount. */
    fun cacheReadMultiplier(model: String): Double = CACHE_READ_MULTIPLIERS.longestPrefixMatch(normalizeModel(model)) ?: 1.0

    private fun <V> Map<String, V>.longestPrefixMatch(model: String): V? =
        this[model] ?: entries.filter { model.startsWith(it.key) }.maxByOrNull { it.key.length }?.value

    /**
     * Estimate cost in USD for a given model and token counts.
     *
     * @param model The model identifier
     * @param promptTokens Number of input/prompt tokens
     * @param completionTokens Number of output/completion tokens
     * @param cacheReadTokens Prompt tokens served from the prompt cache (part of [promptTokens])
     * @param cacheWriteTokens Prompt tokens written to the prompt cache (part of [promptTokens])
     * @return Estimated cost in USD, or null if pricing data is unavailable
     */
    fun estimateCost(
        model: String,
        promptTokens: Int,
        completionTokens: Int,
        cacheReadTokens: Int = 0,
        cacheWriteTokens: Int = 0,
    ): Double? {
        val pricing = pricingFor(model) ?: return null
        val normalized = normalizeModel(model)
        val read = cacheReadTokens.coerceIn(0, promptTokens)
        // only Anthropic bills cache writes separately; OpenAI caches for free and reports reads only
        val write = if (normalized.startsWith(ANTHROPIC_PREFIX)) cacheWriteTokens.coerceIn(0, promptTokens - read) else 0
        val readMultiplier = CACHE_READ_MULTIPLIERS.longestPrefixMatch(normalized) ?: 1.0
        val inputUnits = (promptTokens - read - write) + read * readMultiplier + write * cacheWriteMultiplier
        return (inputUnits / TOKENS_PER_PRICING_UNIT) * pricing.first +
            (completionTokens / TOKENS_PER_PRICING_UNIT) * pricing.second
    }

    companion object {
        private const val TOKENS_PER_PRICING_UNIT = 1000.0

        const val PROMPT_CACHE_TTL_5M = "5m"
        const val PROMPT_CACHE_TTL_1H = "1h"

        /** Anthropic cache writes relative to the base input price: 1.25x for the 5-minute TTL, 2x for 1 hour. */
        private const val ANTHROPIC_PREFIX = "claude-"
        private const val CACHE_WRITE_5M_MULTIPLIER = 1.25
        private const val CACHE_WRITE_1H_MULTIPLIER = 2.0

        /**
         * Cache reads relative to the base input price, by longest model prefix: Anthropic 0.1x except Claude
         * Fable 5.1 ($0.25/MTok) and Opus 5.5 ($0.20/MTok); OpenAI gpt-4o cached input 0.5x. Unlisted models
         * are priced as plain input.
         */
        private val CACHE_READ_MULTIPLIERS: Map<String, Double> = mapOf(
            ANTHROPIC_PREFIX to 0.1,
            "claude-fable-5-1" to 0.025,
            "claude-opus-5-5" to 0.05,
            "gpt-4o" to 0.5,
        )

        /** `ai.pricing` is per million tokens; the table is per 1K. */
        private const val PRICING_UNITS_PER_MTOK = 1000.0

        /** Estimator with `ai.pricing` entries (USD per million tokens) layered over [DEFAULT_PRICING]. */
        fun fromConfig(
            pricing: Map<String, ModelPrice>,
            promptCacheTtl: String = PROMPT_CACHE_TTL_5M,
        ): AiCostEstimator =
            AiCostEstimator(
                pricing.mapValues { (_, p) -> Pair(p.input / PRICING_UNITS_PER_MTOK, p.output / PRICING_UNITS_PER_MTOK) },
                promptCacheTtl,
            )

        private const val COST_DECIMAL_PRECISION = 10000

        /**
         * Cost per 1K tokens in USD as (input, output) pairs. Keys match by longest prefix, so
         * dated ids (`claude-haiku-4-5-20251001`) need no row of their own.
         * Prices are approximate and may not reflect current provider pricing; override with `ai.pricing`.
         * OpenAI rows from https://developers.openai.com/api/docs/pricing and Gemini rows from
         * https://ai.google.dev/gemini-api/docs/pricing (standard tier, short context), both checked 2026-10-25; DeepSeek and Kimi
         * rows from https://openrouter.ai/api/v1/models, checked 2026-11-02.
         */
        val DEFAULT_PRICING: Map<String, Pair<Double, Double>> = mapOf(
            "gpt-5.6-sol" to Pair(0.004, 0.020),
            "gpt-5.6-terra" to Pair(0.002, 0.012),
            "gpt-5.6-luna" to Pair(0.0002, 0.0012),
            "gpt-5.5" to Pair(0.005, 0.030),
            "gpt-5.5-pro" to Pair(0.030, 0.180),
            "gpt-5.4-pro" to Pair(0.030, 0.180),
            "gpt-5.2-pro" to Pair(0.021, 0.168),
            "gpt-5-pro" to Pair(0.015, 0.120),
            "gpt-5.3-codex" to Pair(0.00175, 0.014),
            "gpt-5.4" to Pair(0.0025, 0.015),
            "gpt-5.4-mini" to Pair(0.00075, 0.0045),
            "gpt-5.4-nano" to Pair(0.0002, 0.00125),
            "gpt-5.2" to Pair(0.00175, 0.014),
            "gpt-5.1" to Pair(0.00125, 0.010),
            "gpt-5" to Pair(0.00125, 0.010),
            "gpt-5-mini" to Pair(0.00025, 0.002),
            "gpt-5-nano" to Pair(0.00005, 0.0004),
            "gpt-4.1" to Pair(0.002, 0.008),
            "gpt-4.1-mini" to Pair(0.0004, 0.0016),
            "gpt-4o" to Pair(0.0025, 0.010),
            "gpt-4o-mini" to Pair(0.00015, 0.0006),
            "gpt-4-turbo" to Pair(0.010, 0.030),
            "claude-fable-5" to Pair(0.010, 0.050),
            "claude-opus-5-5" to Pair(0.004, 0.020),
            "claude-opus-5" to Pair(0.005, 0.025),
            "claude-sonnet-5" to Pair(0.002, 0.010),
            "claude-haiku-5-5" to Pair(0.0001, 0.0005),
            "claude-opus-4-8" to Pair(0.005, 0.025),
            "claude-opus-4-7" to Pair(0.005, 0.025),
            "claude-opus-4-6" to Pair(0.005, 0.025),
            "claude-opus-4-5" to Pair(0.005, 0.025),
            "claude-sonnet-4-6" to Pair(0.003, 0.015),
            "claude-sonnet-4-5" to Pair(0.003, 0.015),
            "claude-sonnet-4" to Pair(0.003, 0.015),
            "claude-haiku-4-5" to Pair(0.001, 0.005),
            "gemini-3.8-flash" to Pair(0.00075, 0.00375),
            "gemini-3.6-flash" to Pair(0.00075, 0.00375),
            "gemini-3.5-flash-lite" to Pair(0.0003, 0.0025),
            "gemini-3.1-pro" to Pair(0.002, 0.012),
            "gemini-3.1-flash-lite" to Pair(0.00025, 0.0015),
            "gemini-3-flash" to Pair(0.0005, 0.003),
            "gemini-2.5-pro" to Pair(0.00125, 0.010),
            "gemini-2.5-flash" to Pair(0.0003, 0.0025),
            "gemini-2.5-flash-lite" to Pair(0.0001, 0.0004),
            "gemini-2.0-flash" to Pair(0.00010, 0.00040),
            "gemini-1.5-pro" to Pair(0.00125, 0.005),
            "deepseek-v4-flash" to Pair(0.00009, 0.00018),
            "deepseek-v4-pro" to Pair(0.0016, 0.0032),
            "kimi-k2" to Pair(0.0006, 0.0025),
            "kimi-k2.7" to Pair(0.0006, 0.0035),
            "kimi-k2.5" to Pair(0.0006, 0.0025),
            "qwen3" to Pair(0.00040, 0.00160),
            "qwen3.5" to Pair(0.00040, 0.00160),
            "qwen2" to Pair(0.00040, 0.00120),
            "llama3.2" to Pair(0.0, 0.0),
        )

        /**
         * Estimate cost using default pricing.
         *
         * @param model The model identifier
         * @param promptTokens Number of input/prompt tokens
         * @param completionTokens Number of output/completion tokens
         * @return Estimated cost in USD, or null if pricing data is unavailable
         */
        fun estimateCost(
            model: String,
            promptTokens: Int,
            completionTokens: Int
        ): Double? = AiCostEstimator().estimateCost(model, promptTokens, completionTokens)

        /**
         * Format a USD cost value for display.
         *
         * @param usd Cost in USD
         * @return Formatted string like "$0.0042"
         */
        fun formatCost(usd: Double): String {
            val rounded = (usd * COST_DECIMAL_PRECISION).toLong() / COST_DECIMAL_PRECISION.toDouble()
            val wholePart = rounded.toLong()
            val fracPart = ((rounded - wholePart) * COST_DECIMAL_PRECISION).toLong()
            return "\$$wholePart.${fracPart.toString().padStart(4, '0')}"
        }
    }
}

/** One `ai.pricing` entry: USD per million input/output tokens. */
@Serializable
data class ModelPrice(
    val input: Double,
    val output: Double,
)
