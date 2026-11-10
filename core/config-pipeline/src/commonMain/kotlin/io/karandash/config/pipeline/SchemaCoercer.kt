package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.config.ConfigSchemaError
import io.karandash.api.config.ErrorSeverity
import io.karandash.config.schema.ConfigSections
import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.sdk.typed.TypedModule
import io.karandash.sdk.typed.TypedPlatformServiceModule
import io.karandash.sdk.typed.TypedReporterModule
import io.karandash.sdk.typed.TypedRuleModule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Applies module schemas to raw config [JsonElement] trees, generating coercion warnings.
 *
 * Operates on the raw config element and uses the module registry to identify
 * known modules. For [TypedRuleModule] implementations, validates the config
 * section via [io.karandash.sdk.config.deserializeConfigReporting] to catch type errors early.
 * All other modules receive their config section unchanged.
 *
 * @param registry The loaded module registry.
 */
class SchemaCoercer(
    private val registry: ModuleRegistry,
) {
    /**
     * Descriptor for a non-rules config section that can be schema-coerced.
     *
     * @property configKey Top-level config key (e.g. [ConfigSections.REPORTERS]).
     * @property label Human-readable label for warning messages.
     * @property nullToEmptyObject Whether null/missing values should be treated as empty objects (true)
     *   or stored as-is (false, e.g. reporters).
     * @property findModule Returns a registered module for the given key, or null if absent.
     * @property validateSchema Invokes schema validation on the found module; may throw.
     *
     * Not @Serializable: carries lambdas.
     */
    private data class CoerceSectionDef(
        val configKey: String,
        val label: String,
        val nullToEmptyObject: Boolean,
        val findModule: (key: String) -> Any?,
        val validateSchema: (module: Any, section: JsonElement) -> Unit,
    )

    private fun coerceSectionDefs(): List<CoerceSectionDef> =
        listOf(
            CoerceSectionDef(
                configKey = ConfigSections.REPORTERS,
                label = "reporter",
                nullToEmptyObject = false,
                findModule = { formatId -> registry.reporters().firstOrNull { it.formatId == formatId } },
                validateSchema = { module, section ->
                    if (module is TypedReporterModule<*>) validateTypedModule(module, section)
                },
            ),
            CoerceSectionDef(
                configKey = ConfigSections.PLATFORMS,
                label = "platform",
                nullToEmptyObject = true,
                findModule = { moduleId -> registry.platformServices().firstOrNull { ModuleIdNormalizer.matches(it.configKey, moduleId) } },
                validateSchema = { module, section ->
                    if (module is TypedPlatformServiceModule<*>) validateTypedModule(module, section)
                },
            ),
        )

    /**
     * Coerce the raw config element using registered module schemas.
     *
     * @param rawElement The raw config element (typically from ConfigChainParser or bootstrap).
     * @return A [CoercedResult] with section maps, warnings, and errors.
     */
    suspend fun coerce(rawElement: JsonElement): CoercedResult {
        val warnings = mutableListOf<ConfigSchemaError>()
        val errors = mutableListOf<ConfigSchemaError>()

        val rootObj = rawElement as? JsonObject ?: JsonObject(emptyMap())
        val rulesRaw = rootObj[ConfigSections.RULES] as? JsonObject ?: JsonObject(emptyMap())
        val coercedRules = coerceRules(rulesRaw, warnings)

        val sectionResults = mutableMapOf<String, Map<String, JsonElement>>()
        for (def in coerceSectionDefs()) {
            val sectionRaw = rootObj[def.configKey] as? JsonObject ?: JsonObject(emptyMap())
            sectionResults[def.configKey] = coerceSection(
                sectionRaw = sectionRaw,
                warnings = warnings,
                label = def.label,
                onNull = { _ -> if (def.nullToEmptyObject) JsonObject(emptyMap()) else null },
                onBoolean = { _, _ -> null },
                findModule = def.findModule,
                validateSchema = def.validateSchema,
            )
        }

        return CoercedResult(
            rules = coercedRules,
            platforms = sectionResults.getValue(ConfigSections.PLATFORMS),
            reporters = sectionResults.getValue(ConfigSections.REPORTERS),
            errors = errors,
            warnings = warnings,
        )
    }

    /**
     * Generic helper for coercing a config section (reporters, providers, comment-posters, mutators).
     *
     * For each entry in [sectionRaw]:
     * - [JsonObject] values are passed to schema validation directly.
     * - [JsonNull] values: [onNull] returns the element to use, or null to store [JsonNull] and skip validation.
     * - [JsonPrimitive] boolean values: [onBoolean] returns the element to use, or null to store the
     *   primitive and skip validation.
     * - Any other element type emits a warning and stores the raw value as-is.
     *
     * @param sectionRaw Raw section object from the config element.
     * @param warnings Mutable list to accumulate schema warnings.
     * @param label Human-readable section name for warning messages (e.g. "reporter").
     * @param onNull Called when rawConfig is null/[JsonNull]; return resolved element or null to store-and-skip.
     * @param onBoolean Called when rawConfig is a boolean primitive; return resolved element or null to store-and-skip.
     * @param findModule Returns a registered module for the given key, or null if absent.
     * @param validateSchema Invokes schema validation on the found module; may throw.
     */
    private inline fun coerceSection(
        sectionRaw: JsonObject,
        warnings: MutableList<ConfigSchemaError>,
        label: String,
        onNull: (key: String) -> JsonElement?,
        onBoolean: (key: String, value: Boolean) -> JsonElement?,
        findModule: (key: String) -> Any?,
        validateSchema: (module: Any, section: JsonElement) -> Unit,
    ): Map<String, JsonElement> {
        val result = mutableMapOf<String, JsonElement>()
        for ((moduleId, rawConfig) in sectionRaw) {
            val rawSection: JsonElement =
                when {
                    rawConfig is JsonObject -> {
                        rawConfig
                    }

                    rawConfig is JsonNull -> {
                        val resolved = onNull(moduleId)
                        if (resolved == null) {
                            result[moduleId] = JsonNull
                            continue
                        }
                        resolved
                    }

                    rawConfig is JsonPrimitive && rawConfig.booleanOrNull != null -> {
                        val resolved = onBoolean(moduleId, checkNotNull(rawConfig.booleanOrNull) { "booleanOrNull checked non-null above" })
                        if (resolved == null) {
                            result[moduleId] = rawConfig
                            continue
                        }
                        resolved
                    }

                    else -> {
                        warnings.add(
                            ConfigSchemaError(
                                moduleId = moduleId,
                                field = "",
                                message = "Expected object config for $label '$moduleId', got ${rawConfig::class.simpleName}",
                                severity = ErrorSeverity.WARNING,
                            ),
                        )
                        result[moduleId] = rawConfig
                        continue
                    }
                }
            val module = findModule(moduleId)
            if (module != null) {
                try {
                    validateSchema(module, rawSection)
                } catch (e: IllegalArgumentException) {
                    warnings.add(
                        ConfigSchemaError(
                            moduleId = moduleId,
                            field = "",
                            message = "Schema validation warning for $label '$moduleId': ${e.message}",
                            severity = ErrorSeverity.WARNING,
                        ),
                    )
                }
            }
            result[moduleId] = rawSection
        }
        return result
    }

    private suspend fun coerceRules(
        rulesRaw: JsonObject,
        warnings: MutableList<ConfigSchemaError>,
    ): Map<String, JsonElement> {
        val result = mutableMapOf<String, JsonElement>()
        val knownRuleIds = registry.ruleModules().flatMap { it.contributedRules() }.toSet()

        for ((ruleId, ruleConfig) in rulesRaw) {
            val rawSection: JsonElement =
                when {
                    ruleConfig is JsonObject -> {
                        ruleConfig
                    }

                    ruleConfig is JsonNull -> {
                        JsonObject(emptyMap())
                    }

                    else -> {
                        warnings.add(
                            ConfigSchemaError(
                                moduleId = ruleId,
                                field = "",
                                message = "Expected object config for rule '$ruleId', got ${ruleConfig::class.simpleName}",
                                severity = ErrorSeverity.WARNING,
                            ),
                        )
                        result[ruleId] = ruleConfig
                        continue
                    }
                }

            if (ruleId !in knownRuleIds) {
                result[ruleId] = rawSection
                continue
            }

            val module = registry.ruleModules().firstOrNull { ruleId in it.contributedRules() }
            if (module is TypedRuleModule<*>) {
                try {
                    validateTypedModule(module, rawSection)
                } catch (e: IllegalArgumentException) {
                    warnings.add(
                        ConfigSchemaError(
                            moduleId = ruleId,
                            field = "",
                            message = "Schema validation warning for '$ruleId': ${e.message}",
                            severity = ErrorSeverity.WARNING,
                        ),
                    )
                }
            }

            result[ruleId] = rawSection
        }

        return result
    }
}

/**
 * Validates a [TypedModule]'s config section via strict deserialization. The check runs before `${VAR}`
 * interpolation, so fields whose whole value is a placeholder are left out: their type is known only once the
 * variable is resolved (`max-length: ${LEN}` is an int at run time).
 *
 * @throws Exception if the section cannot be deserialized - callers catch and emit a warning.
 */
private fun validateTypedModule(
    module: TypedModule<*>,
    section: JsonElement
) {
    KarandashJson.Default.decodeFromJsonElement(module.configSerializer, withoutPlaceholders(section))
}

private val PURE_PLACEHOLDER = Regex("^\\$\\{[^}]+}$")

private fun withoutPlaceholders(element: JsonElement): JsonElement =
    if (element is JsonObject) {
        JsonObject(
            element
                .filterValues { value -> !(value is JsonPrimitive && value.isString && PURE_PLACEHOLDER.matches(value.content)) }
                .mapValues { (_, value) -> withoutPlaceholders(value) },
        )
    } else {
        element
    }

/**
 * Result of [SchemaCoercer.coerce] applied to a raw config element.
 *
 * All sections are preserved (rules may have been validated against their schemas).
 * Errors and warnings describe schema issues found during coercion.
 *
 * @param rules Validated rules section (ruleId -> config element).
 * @param platforms Platforms section (passed through unchanged).
 * @param reporters Reporters section (passed through unchanged).
 * @param errors Fatal schema errors (currently reserved; coercion does not throw).
 * @param warnings Non-fatal schema warnings (unknown field, type mismatch, etc.).
 */
@Serializable
data class CoercedResult(
    val rules: Map<String, JsonElement>,
    val platforms: Map<String, JsonElement>,
    val reporters: Map<String, JsonElement>,
    val errors: List<ConfigSchemaError>,
    val warnings: List<ConfigSchemaError>,
)
