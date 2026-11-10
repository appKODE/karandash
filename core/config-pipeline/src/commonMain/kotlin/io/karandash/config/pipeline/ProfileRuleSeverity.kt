package io.karandash.config.pipeline

import io.karandash.sdk.config.PROFILE_RULE_SEVERITY_KEY
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Profile rule configs with the declared scalar run-severity separated from the rule's own config. */
@Serializable
data class SplitProfileRules(
    val ruleConfigs: Map<String, JsonElement>,
    val severities: Map<String, String>,
)

/**
 * Lifts the `@severity` a profile declares in a rule config (see `profileRuleConfigs { rule(id, cfg, severity) }`)
 * out of the rule config. The rule's own `severity` config is untouched.
 */
fun Map<String, JsonElement>.splitProfileSeverity(): SplitProfileRules {
    val severities = mutableMapOf<String, String>()
    val configs = mapValues { (ruleId, config) ->
        val scalar = (config as? JsonObject)?.get(PROFILE_RULE_SEVERITY_KEY) as? JsonPrimitive
        val value = scalar?.contentOrNull
        if (value == null) {
            config
        } else {
            severities[ruleId] = value
            JsonObject((config as JsonObject) - PROFILE_RULE_SEVERITY_KEY)
        }
    }
    return SplitProfileRules(configs, severities)
}
