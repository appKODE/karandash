package io.karandash.api.config

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.support.anyToJsonElement
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class ConfigProperty(
    val name: String,
    val type: ConfigPropertyType,
    val description: String,
    val required: Boolean = false,
    @Contextual val defaultValue: Any? = null,
    val enumValues: List<String>? = null,
    val deprecated: String? = null
) {
    /** [defaultValue] as JSON, or `null` when there is no default. JsonElement-first twin. */
    @ExperimentalKarandashApi
    val defaultJson: JsonElement? get() = defaultValue?.let(::anyToJsonElement)
}
