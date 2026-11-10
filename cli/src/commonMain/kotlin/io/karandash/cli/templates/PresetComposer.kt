package io.karandash.cli.templates

import io.karandash.api.support.mapToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Composes multiple YAML template presets using deep merge strategy.
 * Supports merging base and platform templates.
 */
object PresetComposer {
    /**
     * Compose a configuration from base and platform templates.
     *
     * @param baseTemplate Name of the base template (required)
     * @param platformTemplate Name of the platform template (optional)
     * @return Merged configuration object
     * @throws IllegalArgumentException if base or platform template not found
     */
    fun compose(
        baseTemplate: String,
        platformTemplate: String? = null,
    ): JsonObject {
        val base = PresetRegistry.getBaseTemplate(baseTemplate)
            ?: throw IllegalArgumentException("Base template not found: $baseTemplate")
        val baseObject = mapToJsonElement(base)
        if (platformTemplate == null) return baseObject

        val platform = PresetRegistry.getPlatformTemplate(platformTemplate)
            ?: throw IllegalArgumentException("Platform template not found: $platformTemplate")
        return deepMerge(baseObject, mapToJsonElement(platform))
    }

    /**
     * Deep merge two objects with these rules:
     * - Nested objects: merge recursively
     * - Arrays: append (don't replace)
     * - Primitives: later value wins
     */
    private fun deepMerge(
        base: JsonObject,
        overlay: JsonObject
    ): JsonObject {
        val result = base.toMutableMap()
        overlay.forEach { (key, overlayValue) ->
            val baseValue = result[key]
            result[key] = when {
                baseValue is JsonObject && overlayValue is JsonObject -> deepMerge(baseValue, overlayValue)
                baseValue is JsonArray && overlayValue is JsonArray -> JsonArray(baseValue + overlayValue)
                else -> overlayValue
            }
        }
        return JsonObject(result)
    }
}
