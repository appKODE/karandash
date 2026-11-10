package io.karandash.plugins.ai.core.router

import io.karandash.plugins.ai.core.config.RoutingConfig
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger

/**
 * Selects AI models for a given analyzer based on routing config.
 *
 * Returns an ordered list of models to try: primary model first, then fallbacks.
 * The selection is driven by [RoutingRule] matching and [ModelTier] ordering.
 *
 * @param config The routing configuration with tiers and rules.
 * @param logger Logger for diagnostic output.
 */
class ModelRouter(
    private val config: ModelRouterConfig,
    private val logger: Logger = NoOpLogger,
) {
    /**
     * Select the best models for a given analyzer context.
     *
     * Returns an ordered list of model identifiers to try. The first entry
     * is the primary model; subsequent entries are fallbacks from lower tiers.
     *
     * @param analyzerId The analyzer requesting a model.
     * @return Ordered list of model identifiers (may be empty if no tiers configured).
     */
    fun selectModels(analyzerId: String): List<String> {
        if (config.tiers.isEmpty()) return emptyList()

        val tierName = findTierName(analyzerId)
        val primaryTier = config.tiers.find { it.name == tierName }
        if (primaryTier == null) {
            logger.debug("Model router: tier '$tierName' not found for analyzer '$analyzerId'")
            return emptyList()
        }

        val models = mutableListOf<String>()
        models.addAll(primaryTier.models)

        if (config.fallback.enabled) {
            appendFallbackModels(models, primaryTier)
        }

        return models
    }

    /**
     * Select a single primary model for an analyzer.
     *
     * @param analyzerId The analyzer requesting a model.
     * @return The primary model identifier, or null if no models configured.
     */
    fun selectPrimaryModel(analyzerId: String): String? = selectModels(analyzerId).firstOrNull()

    private fun findTierName(analyzerId: String): String {
        val matchingRule = config.rules.firstOrNull { rule ->
            rule.match == null || analyzerId.contains(rule.match)
        }
        return matchingRule?.tier
            ?: config.tiers.firstOrNull()?.name
            ?: ""
    }

    private fun appendFallbackModels(
        models: MutableList<String>,
        primaryTier: ModelTier,
    ) {
        val tierIndex = config.tiers.indexOf(primaryTier)
        for (i in (tierIndex + 1) until config.tiers.size) {
            models.addAll(config.tiers[i].models)
        }
    }

    companion object {
        /**
         * `ai.routing`: picks a rule's model from the MR's process risk (the risk router is this class).
         * Below [RoutingConfig.riskThreshold] the rule runs on the cheapest (last) tier's first model; at or above it,
         * on the first model of the tier [selectPrimaryModel] picks for the rule. `quality-first` and an unknown
         * risk always take the rule's tier. Null when routing is off or has no tiers.
         */
        fun route(
            config: RoutingConfig?,
            ruleId: String,
            riskScore: Double?,
        ): String? {
            if (config == null || !config.enabled || config.tiers.isEmpty()) return null
            val ruleModel = ModelRouter(
                ModelRouterConfig(
                    strategy = config.strategy,
                    tiers = config.tiers,
                    rules = config.rules,
                    fallback = FallbackConfig(enabled = false),
                ),
            ).selectPrimaryModel(ruleId)
            if (config.strategy == RoutingStrategy.QUALITY_FIRST || riskScore == null || riskScore >= config.riskThreshold) {
                return ruleModel
            }
            return config.tiers
                .last()
                .models
                .firstOrNull() ?: ruleModel
        }
    }
}
