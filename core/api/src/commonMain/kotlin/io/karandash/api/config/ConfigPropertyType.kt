package io.karandash.api.config

import kotlinx.serialization.Serializable

@Serializable
public enum class ConfigPropertyType {
    STRING,
    INT,
    BOOLEAN,
    STRING_LIST,
    OBJECT,
    OBJECT_LIST,
    ENUM,
}
