@file:OptIn(ExperimentalSerializationApi::class)

package io.karandash.plugins.ai.core.router

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Configuration for AI model routing.
 *
 * Defines model tiers, routing rules, and fallback behavior
 * to control which AI models handle which analysis tasks.
 *
 * @property strategy Overall routing strategy for model selection.
 * @property tiers Ordered list of model tiers (first = highest priority).
 * @property rules Rules that map analyzers to specific tiers.
 * @property fallback Fallback behavior when a model fails.
 */
@Serializable
data class ModelRouterConfig(
    val strategy: RoutingStrategy = RoutingStrategy.BALANCED,
    val tiers: List<ModelTier> = emptyList(),
    val rules: List<RoutingRule> = emptyList(),
    val fallback: FallbackConfig = FallbackConfig(),
)

/**
 * Strategy for selecting AI models across tiers.
 */
@Serializable
enum class RoutingStrategy {
    /** Prefer cheaper models, escalate only when needed. */
    @SerialName("cost-optimized")
    COST_OPTIMIZED,

    /** Always use the highest-tier model. */
    @SerialName("quality-first")
    QUALITY_FIRST,

    /** Balance cost and quality based on analyzer complexity. */
    BALANCED,

    /** Cycle through models in tiers sequentially. */
    @SerialName("round-robin")
    ROUND_ROBIN,
}

/**
 * A named group of AI models at the same quality/cost level.
 *
 * @property name Tier identifier used by [RoutingRule] to reference this tier.
 * @property models Ordered list of model identifiers within this tier.
 */
@Serializable
data class ModelTier(
    val name: String,
    val models: List<String>,
)

/**
 * Maps an analyzer (or pattern) to a specific model tier.
 *
 * @property match Substring of the analyzer (rule) ID this rule applies to. Null matches all analyzers.
 * @property tier Name of the [ModelTier] to use for matching analyzers.
 */
@Serializable
data class RoutingRule(
    val match: String? = null,
    val tier: String,
)

/**
 * Fallback behavior when a model fails or is unavailable.
 *
 * @property enabled Whether fallback to the next model/tier is enabled.
 * @property maxRetries Maximum number of retry attempts across fallback models.
 * @property onFailure Action to take on failure: "next-model" tries the next model in sequence.
 */
@Serializable
data class FallbackConfig(
    val enabled: Boolean = true,
    @SerialName("max-retries")
    @JsonNames("maxRetries")
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    @SerialName("on-failure")
    @JsonNames("onFailure")
    val onFailure: String = "next-model",
) {
    companion object {
        const val DEFAULT_MAX_RETRIES = 2
    }
}
