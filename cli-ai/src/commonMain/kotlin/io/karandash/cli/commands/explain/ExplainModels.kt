package io.karandash.cli.commands.explain

import io.karandash.api.config.ConfigProperty
import kotlinx.serialization.Serializable

/**
 * Report for a single config property within a module.
 *
 * @param property The schema-declared property definition.
 * @param actualValue The value from the user's config (null if not set).
 * @param isSet Whether the user explicitly set this property.
 * @param isValid Whether the value matches the expected type.
 * @param issue Human-readable issue description (type mismatch, invalid enum, etc.).
 *
 * Not @Serializable: [actualValue] is an untyped `Any?` config value.
 */
data class FieldReport(
    val property: ConfigProperty,
    val actualValue: Any?,
    val isSet: Boolean,
    val isValid: Boolean,
    val issue: String? = null,
)

/**
 * Report for an unknown key found in the user's config that doesn't match any schema property.
 *
 * @param key The unrecognized key from the user's config.
 * @param suggestion A "did you mean?" suggestion if a close match exists, or null.
 */
@Serializable
data class UnknownKeyReport(
    val key: String,
    val suggestion: String?,
)

/**
 * Complete report for a single module (rule, provider, reporter, etc.).
 *
 * @param moduleId The module identifier (e.g. "title-format", "gitlab").
 * @param moduleType Human-readable type label (e.g. "Rule", "Provider").
 * @param description Module description from [io.karandash.api.Module.description].
 * @param isConfigured Whether the user's config includes this module.
 * @param isDefaultEnabled Whether the module is enabled by zero-config defaults.
 * @param fields Per-property reports for all schema-declared fields.
 * @param unknownKeys Keys in the user's config that don't match any schema property.
 * @param missingRequired Schema-required properties not present in the user's config.
 *
 * Not @Serializable: holds [FieldReport], which carries an untyped `Any?`.
 */
data class ModuleReport(
    val moduleId: String,
    val moduleType: String,
    val description: String,
    val isConfigured: Boolean,
    val isDefaultEnabled: Boolean,
    val fields: List<FieldReport>,
    val unknownKeys: List<UnknownKeyReport>,
    val missingRequired: List<ConfigProperty>,
) {
    /** True when all fields are valid and no unknown keys or missing required fields exist. */
    val isHealthy: Boolean
        get() = fields.all { it.isValid } && unknownKeys.isEmpty() && missingRequired.isEmpty()
}

/**
 * Top-level result of explaining a config against the module registry.
 *
 * @param configPath Path to the discovered config file, or null if zero-config.
 * @param configuredModules Reports for modules the user explicitly configured.
 * @param availableModules Reports for modules available but not configured.
 * @param globalIssues Top-level config issues (unknown top-level keys, etc.).
 *
 * Not @Serializable: holds [ModuleReport], which carries an untyped `Any?`.
 */
data class ExplainResult(
    val configPath: String?,
    val configuredModules: List<ModuleReport>,
    val availableModules: List<ModuleReport>,
    val globalIssues: List<String>,
) {
    /** True when every configured module is healthy and no global issues exist. */
    val isHealthy: Boolean
        get() = configuredModules.all { it.isHealthy } && globalIssues.isEmpty()
}
