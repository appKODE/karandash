package io.karandash.config.pipeline

import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RuleMode
import io.karandash.config.RulesConfig
import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Synthesizes rule configs from the top-level `ai:` config block.
 *
 * Converts AI feature toggles (e.g. `ai.codeSuggestions: true`) into
 * individual rule configs that the validation engine can execute.
 * Extracted from [ConfigMapParser] for single-responsibility.
 */
internal object AiConfigSynthesizer {
    private const val AI_DEFAULT_PRIORITY = 200
    private const val CUSTOM_RULE_PREFIX = "ai-"

    // The AI module's checklist rule (`AiRuleIds.CHECKS`, `pre-merge-checks`), camelCased; core cannot import it.
    private const val CHECKS_RULE_ID = "preMergeChecks"

    // `AiRuleIds.FIX_SUGGESTIONS`, camelCased. It fixes the deterministic rules' failures, so it runs in pass 2
    // (after them) unless its own `mode` says otherwise.
    private const val FIX_SUGGESTIONS_RULE_ID = "fixSuggestions"
    private val EMPTY_JSON_OBJECT: JsonElement = JsonObject(emptyMap())

    /**
     * Parse the top-level `ai:` config block, normalizing keys and interpolating env vars.
     *
     * @return Normalized AI config as a [JsonElement], or null when no `ai:` block is present or it sets
     *   `enabled: false` (AI off: no AI module, rules, state or warnings).
     */
    fun parseAiBlock(data: JsonObject?): JsonElement? {
        // Keys and values are already normalized by the top-level deepNormalizeKeys +
        // deepInterpolateValues pass in parseFullConfigObj - return as-is.
        return data?.takeIf { it.isNotEmpty() && (it["enabled"] as? JsonPrimitive)?.booleanOrNull != false }
    }

    /**
     * Synthesize rule configs from AI feature toggles in the `ai:` block.
     *
     * For each enabled AI sub-feature, creates a rule config entry and meta
     * so the validation engine treats it as a regular rule.
     */
    fun synthesizeRuleConfigs(
        aiBlock: JsonObject,
        existingRules: RulesConfig,
        aiSubRuleKeys: Map<String, String>,
    ): RulesConfig {
        val newRuleConfigs = existingRules.ruleConfigs.toMutableMap()
        val newRuleMeta = existingRules.ruleMeta.toMutableMap()
        val defaultAiPriority = (aiBlock["priority"] as? JsonPrimitive)?.intOrNull ?: AI_DEFAULT_PRIORITY
        val defaultAiMode = RuleMode.fromString((aiBlock["mode"] as? JsonPrimitive)?.content)
        val defaultAiConcernArea = (aiBlock["concernArea"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        for ((featureKey, subRuleId) in aiSubRuleKeys) {
            val subConfig = aiBlock[featureKey] ?: continue
            val enabled = when (subConfig) {
                is JsonObject -> (subConfig["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
                is JsonPrimitive -> subConfig.booleanOrNull ?: continue
                else -> continue
            }
            if (!enabled) continue

            val camelRuleId = ConfigKeyNormalizer.kebabToCamelCase(subRuleId)
            if (camelRuleId !in newRuleConfigs) {
                newRuleConfigs[camelRuleId] = EMPTY_JSON_OBJECT
            }
            if (camelRuleId !in newRuleMeta) {
                val mode = if (camelRuleId == FIX_SUGGESTIONS_RULE_ID && defaultAiMode == RuleMode.NORMAL) {
                    RuleMode.SUPPLEMENT
                } else {
                    defaultAiMode
                }
                newRuleMeta[camelRuleId] = buildRuleMeta(subConfig, defaultAiPriority, mode, defaultAiConcernArea)
            }
        }

        val customRules = aiBlock["customRules"] as? JsonArray
        customRules?.forEach { item ->
            val ruleObj = item as? JsonObject ?: return@forEach
            // Keys already normalized by the top-level deepNormalizeKeys pass.
            val rawId = (ruleObj["id"] as? JsonPrimitive)?.content ?: return@forEach
            // The AI module registers custom rules as `ai-<id>` (CustomAiRule.fromPluginConfig); key the rule
            // config the same way, camelCased like every other synthesized rule, so the engine's lookup and
            // enablement both resolve to that handler.
            val id = ConfigKeyNormalizer.kebabToCamelCase(
                if (rawId.startsWith(CUSTOM_RULE_PREFIX)) rawId else CUSTOM_RULE_PREFIX + rawId,
            )
            if (id !in newRuleConfigs) {
                newRuleConfigs[id] = EMPTY_JSON_OBJECT
            }
            if (id !in newRuleMeta) {
                newRuleMeta[id] = buildRuleMeta(ruleObj, defaultAiPriority, defaultAiMode, defaultAiConcernArea)
            }
        }

        val checks = (aiBlock["checks"] as? JsonArray)?.isNotEmpty() == true
        val ticketCompliance = (aiBlock["checksTicketCompliance"] as? JsonPrimitive)?.booleanOrNull == true
        if (checks || ticketCompliance) {
            newRuleConfigs.getOrPut(CHECKS_RULE_ID) { EMPTY_JSON_OBJECT }
            newRuleMeta.getOrPut(
                CHECKS_RULE_ID
            ) { buildRuleMeta(EMPTY_JSON_OBJECT, defaultAiPriority, defaultAiMode, defaultAiConcernArea) }
        }

        return existingRules.copy(ruleConfigs = newRuleConfigs, ruleMeta = newRuleMeta)
    }

    private fun buildRuleMeta(
        subConfig: JsonElement,
        defaultPriority: Int,
        defaultMode: RuleMode,
        defaultConcernArea: String?,
    ): RuleExecutionMeta {
        val obj = subConfig as? JsonObject
        val priority = (obj?.get("priority") as? JsonPrimitive)?.intOrNull ?: defaultPriority
        val mode = RuleMode.fromString((obj?.get("mode") as? JsonPrimitive)?.content).let {
            if (it == RuleMode.NORMAL && defaultMode != RuleMode.NORMAL) defaultMode else it
        }
        val concernArea = (obj?.get("concernArea") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: defaultConcernArea
        return RuleExecutionMeta(priority = priority, mode = mode, concernArea = concernArea)
    }
}
