package io.karandash.config.pipeline

import io.karandash.api.Module
import io.karandash.config.Config
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Resolves the effective enabled/disabled state for each module from config.
 *
 * Resolution order (first match wins):
 * 1. Boolean shorthand in section map: `rules: { draft-prevention: false }` -> disabled
 * 2. Explicit `enabled: bool` in module config object
 * 3. Fallback: [Module.defaultEnabled]
 *
 * Applies to ALL module types: rules, reporters, providers, comment-posters, mutators.
 */
class ModuleEnablementResolver(
    private val logger: Logger = NoOpLogger,
) {
    /**
     * @param configRoot the fully-resolved config JsonObject (after extends, interpolation)
     * @return map of module-id -> enabled (only includes modules with explicit config or non-default state)
     */
    fun resolve(configRoot: JsonObject): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        for (sectionKey in SECTION_KEYS) {
            val section = configRoot[sectionKey] as? JsonObject ?: continue
            resolveSection(section, result)
        }
        return result
    }

    /**
     * Resolves enablement from the **merged** [Config] (post-extends, post-interpolation).
     *
     * This must be used instead of [resolve] when configs use `extends:`, because
     * `rawElement` only contains the child config's keys - inherited rule blocks are absent,
     * causing modules with [Module.defaultEnabled] = false to be silently skipped.
     */
    fun resolveFromConfig(config: Config): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        resolveSectionEntries(config.rules.ruleConfigs, result)
        config.reporters?.let { resolveSectionEntries(it, result) }
        config.platforms?.let { resolveSectionEntries(it, result) }
        config.aiReports?.let { resolveSectionEntries(it, result) }
        config.aiMetrics?.let { resolveSectionEntries(it, result) }
        config.enrichmentProviders?.let { resolveSectionEntries(it, result) }
        val disabled = result.filterValues { !it }
        if (disabled.isNotEmpty()) {
            logger.debug("Module enablement: ${disabled.keys.joinToString()} explicitly disabled")
        }
        return result
    }

    private fun resolveSectionEntries(
        entries: Map<String, JsonElement>,
        result: MutableMap<String, Boolean>,
    ) {
        for ((moduleId, value) in entries) {
            val resolved = resolveModuleEnabled(value)
            if (resolved != null) {
                result[ConfigKeyNormalizer.kebabToCamelCase(moduleId)] = resolved
            }
        }
    }

    private fun resolveSection(
        section: JsonObject,
        result: MutableMap<String, Boolean>,
    ) {
        for ((moduleId, value) in section) {
            val resolved = resolveModuleEnabled(value)
            if (resolved != null) {
                result[ConfigKeyNormalizer.kebabToCamelCase(moduleId)] = resolved
            }
        }
    }

    private fun resolveModuleEnabled(value: kotlinx.serialization.json.JsonElement): Boolean? {
        // Boolean shorthand - `rules: { draft-prevention: false }`
        if (value is JsonPrimitive) {
            val boolValue = value.booleanOrNull
            if (boolValue != null) return boolValue
            // Non-boolean primitive (e.g. string "true") - not a shorthand, skip
            return null
        }

        // Object config - enabled by presence; respect explicit `enabled: false`
        if (value is JsonObject) {
            val enabledElement = value["enabled"]
            if (enabledElement is JsonPrimitive) {
                val boolValue = enabledElement.booleanOrNull
                if (boolValue != null) return boolValue
            }
            // Configured with an object = enabled by presence (overrides defaultEnabled = false)
            return true
        }

        // No explicit enablement - return null to indicate fallback to Module.defaultEnabled
        return null
    }

    companion object {
        /** Config section keys to inspect for module enablement. */
        private val SECTION_KEYS = listOf(
            "rules",
            "reporters",
            "platforms",
        )

        /**
         * Determines if a module is enabled given the resolved enablement map.
         *
         * @param moduleId the module identifier
         * @param enabledModules the resolved enablement map from [resolve]
         * @param module the module instance (for [Module.defaultEnabled] fallback)
         * @return true if the module should be executed
         */
        fun isEnabled(
            moduleId: String,
            enabledModules: Map<String, Boolean>,
            module: Module,
        ): Boolean = enabledModules[moduleId] ?: module.defaultEnabled
    }
}
