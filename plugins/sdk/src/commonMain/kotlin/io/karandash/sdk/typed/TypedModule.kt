package io.karandash.sdk.typed

import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Module
import io.karandash.sdk.config.ConfigProperty
import io.karandash.sdk.config.configFieldsToProperties
import io.karandash.sdk.config.configSchemaFromDescriptor
import io.karandash.sdk.config.sdkConfigMapJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Base interface for modules that use typed configuration.
 *
 * Typed modules derive [configSchema] and [defaultConfig] automatically from
 * a [KSerializer], so schema declarations are not written by hand and
 * schema and parsing stay in sync.
 *
 * @param C The typed config class for this module.
 */
public interface TypedModule<C : Any> : Module {
    /**
     * The [KSerializer] used to deserialize config JSON into [C].
     */
    public val configSerializer: KSerializer<C>

    override suspend fun configSchema(): List<ConfigProperty> =
        configFieldsToProperties(configSchemaFromDescriptor(configSerializer.descriptor))

    /**
     * Returns the default configuration as a [JsonElement].
     *
     * Deserializes an empty [JsonObject] (which yields all-defaults) and
     * re-serializes with [sdkConfigMapJson] to include default values in the output.
     *
     * Falls back to an empty [JsonObject] if the config has required fields
     * without defaults (e.g. [JiraTicketConfig.projectKeys]).
     */
    override suspend fun defaultConfig(): JsonElement =
        try {
            val defaults = KarandashJson.Default.decodeFromJsonElement(configSerializer, JsonObject(emptyMap()))
            sdkConfigMapJson.encodeToJsonElement(configSerializer, defaults)
        } catch (_: kotlinx.serialization.SerializationException) {
            JsonObject(emptyMap())
        }
}
