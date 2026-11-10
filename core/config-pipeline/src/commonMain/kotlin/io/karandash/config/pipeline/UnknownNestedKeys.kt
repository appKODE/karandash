package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.errors.KarandashError
import io.karandash.cache.CacheConfig
import io.karandash.config.AutoApproveConfig
import io.karandash.config.DiagnosticConfig
import io.karandash.config.DisplayConfig
import io.karandash.config.HttpConfig
import io.karandash.config.LimitsConfig
import io.karandash.config.ProfileOverridesConfig
import io.karandash.config.ReportConfig
import io.karandash.config.RuleEnrichmentConfig
import io.karandash.config.RulesConfig
import io.karandash.config.SecurityConfig
import io.karandash.config.TimeoutConfig
import io.karandash.config.keys.PolicyKeys
import io.karandash.config.schema.ConfigSections
import io.karandash.normalize.ConfigKeyNormalizer.kebabToCamelCase
import io.karandash.normalize.deepNormalizeKeys
import io.karandash.sdk.config.unknownConfigKeys
import io.karandash.sdk.typed.TypedModule
import io.karandash.utils.SuggestionEngine
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Finds keys inside built-in config sections and typed rules' configs that no field reads
 * ([KarandashError.UNKNOWN_YAML_CONFIG_KEY]). Warn-only: an unknown key never fails the run.
 */
object UnknownNestedKeys {
    private const val PROFILE_OVERRIDES = "profile-overrides"
    private val RULE_ENRICHMENT = kebabToCamelCase(ConfigSections.RULE_ENRICHMENT)
    private val SECTIONS: Map<String, SerialDescriptor> = mapOf(
        ConfigSections.CACHE to CacheConfig.serializer().descriptor,
        ConfigSections.TIMEOUTS to TimeoutConfig.serializer().descriptor,
        ConfigSections.HTTP to HttpConfig.serializer().descriptor,
        ConfigSections.LIMITS to LimitsConfig.serializer().descriptor,
        ConfigSections.DISPLAY to DisplayConfig.serializer().descriptor,
        ConfigSections.REPORT to ReportConfig.serializer().descriptor,
        ConfigSections.SECURITY to SecurityConfig.serializer().descriptor,
        ConfigSections.DIAGNOSTICS to DiagnosticConfig.serializer().descriptor,
        ConfigSections.AUTO_APPROVE to AutoApproveConfig.serializer().descriptor,
        PROFILE_OVERRIDES to ProfileOverridesConfig.serializer().descriptor,
    ).mapKeys { kebabToCamelCase(it.key) }

    /**
     * One warning per unknown key of the built-in sections of the merged [root], then per unknown key of each
     * rule config in [rules] whose module is a [TypedModule] (meta keys such as `severity` are already split off; `enabled`
     * stays in the rule config but is the engine's on/off switch, never a typed field), then per unknown key of each
     * module-owned top-level block in [extensions] whose module is a [TypedModule].
     */
    suspend fun warnings(
        root: JsonElement,
        rules: RulesConfig,
        registry: ModuleRegistry,
        extensions: Map<String, JsonElement> = emptyMap(),
    ): List<String> {
        val code = KarandashError.UNKNOWN_YAML_CONFIG_KEY.bracketCode
        val normalized = deepNormalizeKeys(root, ConfigMapParser.preserveKeysFor(registry)) as? JsonObject
        val sectionKeys = SECTIONS.flatMap { (section, descriptor) ->
            normalized
                ?.get(section)
                // Fully normalized: a section is all schema keys, and `limits.patterns` is not the user-keyed top-level `patterns`.
                ?.let { unknownConfigKeys(descriptor, deepNormalizeKeys(it)) }
                .orEmpty()
                .map { "$section.$it" }
        }
        val typedByRule = registry
            .ruleModules()
            .flatMap { module ->
                val typed = module as? TypedModule<*> ?: return@flatMap emptyList()
                module.contributedRules().map { kebabToCamelCase(it) to typed }
            }.toMap()
        val ruleKeys = rules.ruleConfigs.flatMap { (ruleId, config) ->
            typedByRule[ruleId]
                ?.let { unknownConfigKeys(it.configSerializer.descriptor, config) }
                .orEmpty()
                .filter { it != PolicyKeys.ENABLED }
                .map { "rules.$ruleId.$it" }
        }
        // `ruleEnrichment` children are user rule ids (kept as written); each one's value is a RuleEnrichmentConfig.
        val enrichmentKeys = (normalized?.get(RULE_ENRICHMENT) as? JsonObject).orEmpty().flatMap { (ruleId, binding) ->
            unknownConfigKeys(RuleEnrichmentConfig.serializer().descriptor, deepNormalizeKeys(binding))
                .map { "$RULE_ENRICHMENT.$ruleId.$it" }
        }
        return (sectionKeys + enrichmentKeys + ruleKeys).map { "$code Config key '$it' is not a known setting and is ignored." } +
            moduleBlockWarnings(extensions, registry)
    }

    /**
     * A module-owned block (`platform:` is the platform output's config) with keys its module does not read is
     * usually a typo of a built-in key (`platforms:`): name the unread keys and the built-in key it is closest to.
     */
    private fun moduleBlockWarnings(
        extensions: Map<String, JsonElement>,
        registry: ModuleRegistry,
    ): List<String> {
        val code = KarandashError.UNKNOWN_YAML_CONFIG_KEY.bracketCode
        val modules = registry.allModules()
        return extensions.flatMap { (key, block) ->
            val module = modules.firstOrNull { it.configKey == key } ?: return@flatMap emptyList()
            val typed = module as? TypedModule<*> ?: return@flatMap emptyList()
            val unknown = unknownConfigKeys(typed.configSerializer.descriptor, block)
            if (unknown.isEmpty()) return@flatMap emptyList()
            val hint = SuggestionEngine
                .closest(key, UnknownTopLevelKeys.known, maxDistance = MODULE_ID_MAX_DISTANCE)
                ?.let { " Did you mean top-level '$it'?" }
                .orEmpty()
            unknown.map { "$code Config key '$key.$it' is not a setting of module '${module.id}' and is ignored.$hint" }
        }
    }
}
