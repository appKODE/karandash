package io.karandash.api.config

import kotlinx.serialization.Serializable

/**
 * Schema field descriptor derived automatically from [kotlinx.serialization.SerialDescriptor].
 *
 * Automatically derived from [kotlinx.serialization.descriptors.SerialDescriptor].
 *
 * @property name Field name as it appears in YAML/JSON config.
 * @property type Field type classification.
 * @property optional Whether the field has a default value (omitting it from config is valid).
 * @property nested For [ConfigFieldType.OBJECT] fields, the nested field schema; for a [ConfigFieldType.LIST] of
 *   objects, the element schema.
 */
@Serializable
public data class ConfigField(
    val name: String,
    val type: ConfigFieldType,
    val optional: Boolean,
    val nested: List<ConfigField>? = null,
)

/**
 * Classification of config field types, derived from [kotlinx.serialization.descriptors.SerialKind].
 */
@Serializable
public enum class ConfigFieldType {
    STRING,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    LIST,
    MAP,
    OBJECT,
    ENUM,
    UNKNOWN;

    /**
     * Converts to the [ConfigPropertyType] used by LSP and bridge modules.
     */
    public fun toConfigPropertyType(): ConfigPropertyType =
        when (this) {
            STRING -> ConfigPropertyType.STRING
            INT -> ConfigPropertyType.INT
            LONG -> ConfigPropertyType.INT
            FLOAT -> ConfigPropertyType.STRING
            DOUBLE -> ConfigPropertyType.STRING
            BOOLEAN -> ConfigPropertyType.BOOLEAN
            LIST -> ConfigPropertyType.STRING_LIST
            MAP -> ConfigPropertyType.OBJECT
            OBJECT -> ConfigPropertyType.OBJECT
            ENUM -> ConfigPropertyType.ENUM
            UNKNOWN -> ConfigPropertyType.STRING
        }
}
