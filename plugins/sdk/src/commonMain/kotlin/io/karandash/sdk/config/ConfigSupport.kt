package io.karandash.sdk.config

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.PluginConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Generic config deserialization using kotlinx.serialization.
 */
public inline fun <reified C : PluginConfig> deserializeConfig(element: JsonElement): C =
    KarandashJson.Default.decodeFromJsonElement(serializer(), element)

/**
 * Generic default config using data class constructor defaults.
 * Uses data class constructor defaults by deserializing an empty JSON object.
 */
public inline fun <reified C : PluginConfig> defaultConfig(): C =
    KarandashJson.Default.decodeFromJsonElement(serializer(), JsonObject(emptyMap()))

/**
 * Generic schema extraction from compiler-generated [SerialDescriptor].
 * Uses the compiler-generated [SerialDescriptor] to derive field names, types, and optionality.
 */
public inline fun <reified C : PluginConfig> configSchema(): List<ConfigField> {
    val descriptor = serializer<C>().descriptor
    return configSchemaFromDescriptor(descriptor)
}

/**
 * Extracts [ConfigField] list from a [SerialDescriptor].
 *
 * Handles nested structures recursively. Used by [configSchema] and
 * the bridge from [TypedRuleModule.configSchema] to [ConfigProperty].
 */
public fun configSchemaFromDescriptor(descriptor: SerialDescriptor): List<ConfigField> =
    (0 until descriptor.elementsCount).map { i ->
        val elementDescriptor = descriptor.getElementDescriptor(i)
        ConfigField(
            name = descriptor.getElementName(i),
            type = elementDescriptor.toConfigFieldType(),
            optional = descriptor.isElementOptional(i),
            nested = elementDescriptor.objectDescriptor()?.let(::configSchemaFromDescriptor),
        )
    }

/**
 * Keys of [element] that [descriptor] does not declare, so decoding drops them, as dotted paths relative to
 * [element]. Recurses into nested classes; `@JsonNames` alternatives count as declared. Map, list,
 * polymorphic and custom-serialized values are not inspected, and a class with no fields (a free-form
 * config) reports nothing. Keys are compared as-is: normalize them to camelCase first, as the config
 * pipeline does before decoding.
 */
@OptIn(ExperimentalSerializationApi::class)
public fun unknownConfigKeys(
    descriptor: SerialDescriptor,
    element: JsonElement,
): List<String> {
    val obj = element as? JsonObject
    if (obj == null || descriptor.kind != StructureKind.CLASS || descriptor.elementsCount == 0) return emptyList()
    val fields = (0 until descriptor.elementsCount)
        .flatMap { i ->
            val alternates = descriptor.getElementAnnotations(i).filterIsInstance<JsonNames>().flatMap { it.names.asList() }
            (alternates + descriptor.getElementName(i)).map { it to descriptor.getElementDescriptor(i) }
        }.toMap()
    return obj.flatMap { (key, value) ->
        fields[key]?.let { child -> unknownConfigKeys(child, value).map { "$key.$it" } } ?: listOf(key)
    }
}

/**
 * Bridges between the [ConfigField]-based schema and the [ConfigProperty]-based schema used by LSP and bridge
 * modules.
 */
public fun configFieldsToProperties(fields: List<ConfigField>): List<ConfigProperty> =
    fields.map { field ->
        ConfigProperty(
            name = field.name,
            // A list whose elements are classes carries their schema in `nested`: an object list, not a string list.
            type = if (field.type == ConfigFieldType.LIST &&
                field.nested != null
            ) {
                ConfigPropertyType.OBJECT_LIST
            } else {
                field.type.toConfigPropertyType()
            },
            description = "",
            required = !field.optional,
        )
    }

/**
 * Deserializes config using a [KSerializer], falling back to the defaults (an empty JSON object
 * decoded via the same serializer) and returning why decoding failed.
 *
 * @return the decoded config (or the defaults on failure) and the decode error message,
 *   `null` when [element] decoded cleanly.
 */
public fun <C> deserializeConfigReporting(
    serializer: KSerializer<C>,
    element: JsonElement,
): Pair<C, String?> =
    try {
        KarandashJson.Default.decodeFromJsonElement(serializer, element) to null
    } catch (e: kotlinx.serialization.SerializationException) {
        KarandashJson.Default.decodeFromJsonElement(serializer, JsonObject(emptyMap())) to e.message.orEmpty()
    } catch (e: IllegalArgumentException) {
        KarandashJson.Default.decodeFromJsonElement(serializer, JsonObject(emptyMap())) to e.message.orEmpty()
    }

/**
 * [deserializeConfigReporting] for the output/format/reporter bridges: a block that fails to decode
 * still falls back to the defaults, but the run logger (from [RunScopedServices]) gets one warn naming
 * the module. Only the first line of the decode error is logged, with any rejected literal value
 * dropped, so a mistyped secret never reaches the log.
 */
@OptIn(ExperimentalKarandashApi::class)
internal suspend fun <C> deserializeConfigWarning(
    serializer: KSerializer<C>,
    element: JsonElement,
    what: String,
): C {
    val (config, error) = deserializeConfigReporting(serializer, element)
    if (error != null && element !is JsonNull) {
        val line = error.lineSequence().first()
        val reason = if (line.startsWith(LITERAL_ERROR_PREFIX)) "wrong value type at ${line.substringAfterLast(" at ")}" else line
        RunScopedServices.current()?.get(RunServiceKeys.LOGGER)?.warn("Invalid config for $what, using defaults: $reason")
    }
    return config
}

private const val LITERAL_ERROR_PREFIX = "Failed to parse literal"

/** The class this field holds, directly or as the elements of a list; `null` for anything else. */
private fun SerialDescriptor.objectDescriptor(): SerialDescriptor? =
    when (kind) {
        is StructureKind.CLASS -> this
        StructureKind.LIST -> getElementDescriptor(0).takeIf { it.kind is StructureKind.CLASS }
        else -> null
    }

/**
 * Maps a [SerialDescriptor] kind to a [ConfigFieldType].
 */
public fun SerialDescriptor.toConfigFieldType(): ConfigFieldType =
    when (kind) {
        PrimitiveKind.STRING -> ConfigFieldType.STRING
        PrimitiveKind.INT -> ConfigFieldType.INT
        PrimitiveKind.LONG -> ConfigFieldType.LONG
        PrimitiveKind.FLOAT -> ConfigFieldType.FLOAT
        PrimitiveKind.DOUBLE -> ConfigFieldType.DOUBLE
        PrimitiveKind.BOOLEAN -> ConfigFieldType.BOOLEAN
        StructureKind.LIST -> ConfigFieldType.LIST
        StructureKind.MAP -> ConfigFieldType.MAP
        StructureKind.CLASS, StructureKind.OBJECT -> ConfigFieldType.OBJECT
        SerialKind.ENUM -> ConfigFieldType.ENUM
        else -> ConfigFieldType.UNKNOWN
    }
