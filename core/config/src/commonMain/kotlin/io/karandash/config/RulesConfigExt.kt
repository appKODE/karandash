package io.karandash.config

import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.json.JsonElement

/**
 * Returns the configuration(s) for a given rule ID as a list of [JsonElement].
 *
 * All rules return a singleton list when configured or an empty list when absent.
 * Falls back to [ModuleIdNormalizer]-based matching when the exact key is not found,
 * so any naming convention (kebab-case, snake_case, camelCase, PascalCase) resolves correctly.
 */
fun RulesConfig.getRuleConfigElements(ruleId: String): List<JsonElement> {
    val key = ModuleIdNormalizer.resolve(ruleId, ruleConfigs.keys) ?: return emptyList()
    return listOfNotNull(ruleConfigs[key])
}
