package io.karandash.cli.commands.explain

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.config.Config
import io.karandash.config.getRuleConfigElements
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.sdk.config.toAnyValue
import io.karandash.utils.SuggestionEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Analyzes a parsed [Config] against the [ModuleRegistry] schema metadata.
 *
 * For every configured module, the explainer checks each user-provided value against the
 * module's [Module.configSchema], detecting type mismatches, unknown keys, and missing
 * required fields. Unconfigured-but-available modules are listed separately.
 *
 * @param registry The loaded module registry (built-in + plugins).
 */
class ConfigExplainer(
    private val registry: ModuleRegistry
) {
    /**
     * Descriptor for a non-rules config section that can be explained.
     *
     * @property type The module type for this section.
     * @property configAccessor Extracts the section's config map from a [Config].
     * @property modules Returns the registered modules for this section.
     */
    @Serializable
    private data class ExplainSectionDef(
        val type: ModuleType,
        val configAccessor: (Config) -> Map<String, JsonElement>?,
        val modules: () -> List<Module>,
    ) {
        val label: String
            get() = type.cliLabel.split('-').joinToString(" ") {
                it.replaceFirstChar { c -> c.uppercaseChar() }
            }
    }

    private fun explainSectionDefs(): List<ExplainSectionDef> =
        listOf(
            ExplainSectionDef(
                type = ModuleType.PLATFORM_SERVICE,
                configAccessor = { it.platforms },
                modules = { registry.platformServices() },
            ),
            ExplainSectionDef(
                type = ModuleType.REPORTER,
                configAccessor = { it.reporters },
                modules = { registry.reporters() },
            ),
        )

    /**
     * Produce a full [ExplainResult] for the given config.
     *
     * @param config The parsed config object.
     * @param configPath Path to the config file (for display), or null for zero-config.
     * @return Complete analysis result.
     */
    suspend fun explain(
        config: Config,
        configPath: String? = null
    ): ExplainResult {
        val configured = mutableListOf<ModuleReport>()
        val available = mutableListOf<ModuleReport>()
        val globalIssues = mutableListOf<String>()

        explainRules(config, configured, available)
        for (def in explainSectionDefs()) {
            explainSection(def.configAccessor(config), def.label, def.modules(), configured, available)
        }

        return ExplainResult(
            configPath = configPath,
            configuredModules = configured,
            availableModules = available,
            globalIssues = globalIssues,
        )
    }

    private suspend fun explainRules(
        config: Config,
        configured: MutableList<ModuleReport>,
        available: MutableList<ModuleReport>,
    ) {
        val configuredRuleIds = config.rules.ruleConfigs.keys
        val allRuleModules = registry.ruleModules()
        val allRuleIds = allRuleModules.flatMap { it.contributedRules() }.toSet()
        val handledRuleIds = mutableSetOf<String>()

        for (ruleModule in allRuleModules) {
            for (ruleId in ruleModule.contributedRules()) {
                handledRuleIds.add(ruleId)
                val userConfig = config.rules.getRuleConfigElements(ruleId).firstOrNull()
                if (userConfig != null) {
                    configured.add(
                        buildModuleReport(
                            moduleId = ruleId,
                            moduleType = "Rule",
                            module = ruleModule,
                            userConfigElement = userConfig,
                            isConfigured = true,
                        )
                    )
                } else {
                    available.add(
                        buildModuleReport(
                            moduleId = ruleId,
                            moduleType = "Rule",
                            module = ruleModule,
                            userConfigElement = JsonObject(emptyMap()),
                            isConfigured = false,
                        )
                    )
                }
            }
        }

        for (unknownRuleId in configuredRuleIds.filter { ModuleIdNormalizer.resolve(it, allRuleIds) == null }) {
            configured.add(
                ModuleReport(
                    moduleId = unknownRuleId,
                    moduleType = "Rule",
                    description = "Unknown rule (not registered in the module registry)",
                    isConfigured = true,
                    isDefaultEnabled = false,
                    fields = emptyList(),
                    unknownKeys = emptyList(),
                    missingRequired = emptyList(),
                )
            )
        }
    }

    private suspend fun explainSection(
        sectionConfig: Map<String, JsonElement>?,
        typeName: String,
        modules: List<Module>,
        configured: MutableList<ModuleReport>,
        available: MutableList<ModuleReport>,
    ) {
        val configMap = sectionConfig ?: emptyMap()
        val moduleById = modules.associateBy { it.configKey.ifEmpty { it.dslName } }

        for ((key, userConfigElement) in configMap) {
            val module = moduleById[key]
            if (module != null) {
                configured.add(
                    buildModuleReport(
                        moduleId = key,
                        moduleType = typeName,
                        module = module,
                        userConfigElement = userConfigElement,
                        isConfigured = true,
                    )
                )
            } else {
                configured.add(
                    ModuleReport(
                        moduleId = key,
                        moduleType = typeName,
                        description = "Unknown $typeName (not registered in the module registry)",
                        isConfigured = true,
                        isDefaultEnabled = false,
                        fields = emptyList(),
                        unknownKeys = emptyList(),
                        missingRequired = emptyList(),
                    )
                )
            }
        }

        for ((key, module) in moduleById) {
            if (key !in configMap) {
                available.add(
                    buildModuleReport(
                        moduleId = key,
                        moduleType = typeName,
                        module = module,
                        userConfigElement = JsonObject(emptyMap()),
                        isConfigured = false,
                    )
                )
            }
        }
    }

    private suspend fun buildModuleReport(
        moduleId: String,
        moduleType: String,
        module: Module,
        userConfigElement: JsonElement,
        isConfigured: Boolean,
    ): ModuleReport {
        val schema = module.configSchema()
        val schemaByName = schema.associateBy { it.name }
        val userConfigObj = userConfigElement as? JsonObject ?: JsonObject(emptyMap())
        val defaultConfigObj = module.defaultConfig() as? JsonObject ?: JsonObject(emptyMap())

        val fields = schema.map { prop ->
            val isSet = prop.name in userConfigObj
            val rawValue = if (isSet) userConfigObj[prop.name] else defaultConfigObj[prop.name]
            val actualValue = rawValue?.toAnyValue()
            val issue = if (isSet) validateType(prop, userConfigObj[prop.name]?.toAnyValue()) else null
            FieldReport(
                property = prop,
                actualValue = actualValue,
                isSet = isSet,
                isValid = issue == null,
                issue = issue,
            )
        }

        val schemaKeys = schemaByName.keys
        val metaKeys = META_KEYS
        val unknownKeys = userConfigObj.keys
            .filter { it !in schemaKeys && it !in metaKeys }
            .map { key ->
                UnknownKeyReport(
                    key = key,
                    suggestion = findClosestMatch(key, schemaKeys),
                )
            }

        val missingRequired = schema.filter { it.required && it.name !in userConfigObj }

        val isDefaultEnabled = module.defaultEnabled

        return ModuleReport(
            moduleId = moduleId,
            moduleType = moduleType,
            description = module.description,
            isConfigured = isConfigured,
            isDefaultEnabled = isDefaultEnabled,
            fields = fields,
            unknownKeys = unknownKeys,
            missingRequired = missingRequired,
        )
    }

    companion object {
        /** Meta keys stripped by ConfigMapParser - not part of module schema. */
        private val META_KEYS = setOf(
            "priority",
            "bail-on-fail",
            "bailOnFail",
            "when",
            "mode",
        )

        private const val MAX_LEVENSHTEIN_DISTANCE = 2

        /**
         * Validate that [value] is compatible with the [prop]'s declared type.
         *
         * @return A human-readable issue string, or null if valid.
         */
        internal fun validateType(
            prop: ConfigProperty,
            value: Any?
        ): String? {
            if (value == null) return null
            return when (prop.type) {
                ConfigPropertyType.STRING -> if (value !is String) "Expected string, got ${typeName(value)}" else null
                ConfigPropertyType.INT -> if (value !is Number) "Expected integer, got ${typeName(value)}" else null
                ConfigPropertyType.BOOLEAN -> if (value !is Boolean) "Expected boolean, got ${typeName(value)}" else null
                ConfigPropertyType.STRING_LIST -> validateStringList(value)
                ConfigPropertyType.ENUM -> validateEnum(prop, value)
                ConfigPropertyType.OBJECT -> if (value !is Map<*, *>) "Expected object, got ${typeName(value)}" else null
                ConfigPropertyType.OBJECT_LIST -> validateObjectList(value)
            }
        }

        private fun validateStringList(value: Any): String? {
            if (value !is List<*>) return "Expected list of strings, got ${typeName(value)}"
            val nonStrings = value.filterNot { it is String }
            if (nonStrings.isNotEmpty()) return "Expected all list elements to be strings"
            return null
        }

        private fun validateEnum(
            prop: ConfigProperty,
            value: Any
        ): String? {
            if (value !is String) return "Expected string (enum), got ${typeName(value)}"
            val allowed = prop.enumValues ?: return null
            if (value !in allowed) return "Invalid value '$value'; allowed: ${allowed.joinToString(", ")}"
            return null
        }

        private fun validateObjectList(value: Any): String? {
            if (value !is List<*>) return "Expected list of objects, got ${typeName(value)}"
            val nonMaps = value.filterNot { it is Map<*, *> }
            if (nonMaps.isNotEmpty()) return "Expected all list elements to be objects"
            return null
        }

        private fun typeName(value: Any): String =
            when (value) {
                is String -> "string"
                is Boolean -> "boolean"
                is Number -> "number"
                is List<*> -> "list"
                is Map<*, *> -> "object"
                else -> value::class.simpleName ?: "unknown"
            }

        /**
         * Find the closest schema key to [input] using Levenshtein distance.
         *
         * @return The closest match if distance <= [MAX_LEVENSHTEIN_DISTANCE], or null.
         */
        internal fun findClosestMatch(
            input: String,
            candidates: Set<String>
        ): String? = SuggestionEngine.closest(input, candidates, MAX_LEVENSHTEIN_DISTANCE)
    }
}
