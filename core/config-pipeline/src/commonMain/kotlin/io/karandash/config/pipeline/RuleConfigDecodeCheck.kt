package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.config.RulesConfig
import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ConfigKeyNormalizer.kebabToCamelCase
import io.karandash.sdk.typed.TypedModule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * Decodes every typed rule's config the way the rule will read it, but with
 * [KarandashJson.RuleConfig], so a value that does not fit stops the load instead of leaving the rule on its
 * defaults. Unknown keys are [UnknownNestedKeys]' concern and a non-object config is the schema coercer's.
 */
object RuleConfigDecodeCheck {
    /** One message per rule config in [rules] that its module's config class rejects. */
    suspend fun errors(
        rules: RulesConfig,
        registry: ModuleRegistry,
    ): List<String> {
        val typedByRule = registry
            .ruleModules()
            .flatMap { module ->
                val typed = module as? TypedModule<*> ?: return@flatMap emptyList()
                module.contributedRules().map { kebabToCamelCase(it) to typed }
            }.toMap()
        return rules.ruleConfigs.mapNotNull { (ruleId, config) ->
            val typed = typedByRule[ruleId]
            if (typed == null || config !is JsonObject) return@mapNotNull null
            decodeError(typed, config)?.let { "Invalid config for rule '$ruleId': $it" }
        }
    }

    private fun decodeError(
        typed: TypedModule<*>,
        config: JsonObject,
    ): String? =
        try {
            KarandashJson.RuleConfig.decodeFromJsonElement(typed.configSerializer, config)
            null
        } catch (e: SerializationException) {
            e.message
                .orEmpty()
                .lineSequence()
                .first()
        } catch (e: IllegalArgumentException) {
            e.message
                .orEmpty()
                .lineSequence()
                .first()
        }
}
