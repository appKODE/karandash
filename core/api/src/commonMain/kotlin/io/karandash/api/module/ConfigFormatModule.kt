package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Represents a pluggable config format parser.
 *
 * Built-in implementations (yaml, json, kts) are registered during bootstrap.
 * External plugins can contribute additional formats by implementing this interface
 * and returning instances from their [Module.initialize] or a dedicated loader.
 *
 * @property formatId Unique format identifier (e.g. "yaml", "json", "kts").
 */
public interface ConfigFormatModule {
    public val formatId: String

    /** File extensions this format handles, without leading dot (e.g. "yaml", "yml", "json"). */
    public val fileExtensions: Set<String> get() = emptySet()

    /**
     * Discovery priority - lower values are tried first during config file discovery.
     *
     * Built-in formats use: KTS = 0, YAML = 10, JSON = 20.
     * External format plugins should use values >= 100 (the default).
     */
    public val priority: Int get() = DEFAULT_PRIORITY

    public companion object {
        /** Default priority for external/community format modules. */
        public const val DEFAULT_PRIORITY: Int = 100
    }

    /**
     * Parse the full config content into a [JsonElement] tree.
     *
     * @param content Raw config file content.
     * @return Parsed representation as [JsonElement] (typically a [JsonObject]).
     */
    public suspend fun parseToJsonElement(content: String): JsonElement

    /**
     * Extract only the `extends:` value from config content without full parsing.
     *
     * Default implementation performs a full parse and extracts the `extends` key.
     * Format modules can override with a more efficient extraction (e.g. regex or partial parse).
     *
     * @param content Raw config file content.
     * @return The raw `extends` value as [JsonElement], or null if absent.
     */
    public suspend fun parseExtendsOnly(content: String): JsonElement? {
        val element = parseToJsonElement(content)
        return if (element is JsonObject) element["extends"] else null
    }
}

/**
 * Extension of [ConfigFormatModule] for formats that need rule schema information
 * to perform accurate parsing (e.g. KTS DSL type-checking).
 *
 * Implementing this interface allows the config format registry to inject rule schemas
 * without downcasting to a concrete format module type.
 */
public interface SchemaAwareFormatModule : ConfigFormatModule {
    /**
     * Returns a format module that type-checks against [schemas] (rule ID to JSON schema).
     *
     * Default: `this` (schemas ignored).
     */
    @ExperimentalKarandashApi
    public fun withRuleSchemas(schemas: JsonObject): ConfigFormatModule = this
}
