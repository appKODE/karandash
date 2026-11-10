package io.karandash.manifest

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads plugin declarations from a `plugins:` config block.
 *
 * Supports two declaration forms:
 *   - String shorthand: `"com.example.my-rule"` → id only, version=null
 *   - Map form: `{id: "com.example.my-rule", version: "1.2.0", source: "https://..."}` → full
 *
 * Built-in module IDs (e.g. `title-format`, `console-reporter`) can appear in declarations
 * but they always load regardless - an INFO log is emitted to avoid confusion.
 */
object ManifestReader {
    /**
     * Parse a raw `plugins:` list into [PluginDeclaration] instances.
     *
     * @param raw The raw value of the `plugins:` key (expected to be `List<*>` or null).
     * @return Parsed declarations; empty list if [raw] is null or not a list.
     */
    fun parsePluginDeclarations(
        raw: Any?,
        logger: Logger = NoOpLogger
    ): List<PluginDeclaration> {
        val list = raw as? List<*> ?: run {
            logger.debug("ManifestReader: unexpected type ${raw?.let { it::class.simpleName }}, returning default")
            return emptyList()
        }
        return list.mapNotNull { item -> parseDeclaration(item) }
    }

    /**
     * Parse a [JsonElement] manifest into a [ManifestData].
     *
     * Extracts the `plugins:` block directly from the [JsonObject] without converting
     * the entire tree to [Map]. Each field value is converted to its Kotlin equivalent
     * only at the point of use, preserving the heterogeneous structure (string shorthands
     * mixed with map forms) expected by [parsePluginsBlock].
     */
    fun parseManifest(
        element: JsonElement,
        logger: Logger = NoOpLogger
    ): ManifestData {
        val obj = element as? JsonObject ?: run {
            logger.debug("ManifestReader: unexpected type ${element::class.simpleName}, returning default")
            return ManifestData()
        }
        val pluginsElement = obj["plugins"]
        val pluginsRaw: Any? = when (pluginsElement) {
            is JsonArray -> pluginsElement.map { jsonElementToRaw(it) }
            is JsonObject -> pluginsElement.entries.associate { (k, v) -> k to jsonElementToRaw(v) }
            is JsonPrimitive -> pluginsElement.contentOrNull
            else -> null
        }
        return parsePluginsBlock(pluginsRaw)
    }

    private fun parsePluginsBlock(raw: Any?): ManifestData {
        if (raw is List<*>) {
            return ManifestData(pluginDeclarations = parsePluginDeclarations(raw))
        }

        val map = raw as? Map<*, *> ?: return ManifestData()

        val onMissing = flexLookup(map, "on-missing")
            ?.toString()
            ?.let { MissingPluginBehavior.fromString(it) }
            ?: MissingPluginBehavior.WARN

        return ManifestData(
            pluginDeclarations = parsePluginDeclarations(map["install"]),
            registryUrl = flexLookup(map, "registry-url")?.toString(),
            pluginDir = map["directory"]?.toString(),
            onMissing = onMissing,
            registryToken = map["token"]?.toString(),
            registryAuthHeader = flexLookup(map, "auth-header")?.toString(),
            allowLocalhost = flexLookup(map, "allow-localhost")?.toString()?.toBooleanStrictOrNull() ?: false,
        )
    }

    /**
     * Looks up a value in a map using normalized identifier matching.
     *
     * Tries the exact key first, then falls back to [ModuleIdNormalizer]-based matching
     * to handle kebab-case, camelCase, and snake_case variants.
     */
    private fun flexLookup(
        data: Map<*, *>,
        kebabKey: String
    ): Any? {
        data[kebabKey]?.let { return it }
        val normalized = ModuleIdNormalizer.normalize(kebabKey)
        return data.entries.firstOrNull { ModuleIdNormalizer.normalize(it.key.toString()) == normalized }?.value
    }

    private fun jsonElementToRaw(element: JsonElement): Any? =
        when (element) {
            is JsonNull -> null
            is JsonPrimitive -> element.booleanOrNull ?: element.intOrNull ?: element.contentOrNull
            is JsonObject -> element.entries.associate { (k, v) -> k to jsonElementToRaw(v) }
            is JsonArray -> element.map { jsonElementToRaw(it) }
        }

    private fun parseDeclaration(item: Any?): PluginDeclaration? =
        when (item) {
            is String -> {
                if (item.isNotBlank()) PluginDeclaration(id = item.trim()) else null
            }

            is Map<*, *> -> {
                val map = item
                val id = map["id"] as? String ?: return null
                PluginDeclaration(
                    id = id,
                    version = map["version"] as? String,
                    source = map["source"] as? String,
                )
            }

            else -> {
                null
            }
        }
}
