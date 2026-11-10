package io.karandash.config.pipeline

import io.karandash.config.Config
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Result of [ConfigMapParser.parseFullConfig] that carries the parsed [Config],
 * the names of any environment variables that were referenced but not set,
 * and the names of any denied variables that were kept as placeholders.
 */
@Serializable
data class ConfigParseResult(
    val config: Config,
    val missingEnvVars: Set<String> = emptySet(),
    val deniedEnvVars: Set<String> = emptySet(),
)

private const val DISABLED_KEY = "__disabled"

/** Sentinel [JsonElement] marking a module as explicitly disabled. */
val DISABLED_MODULE_ELEMENT: JsonElement = JsonObject(mapOf(DISABLED_KEY to JsonPrimitive(true)))

/** Returns true when [config] represents an explicitly disabled module entry. */
fun isModuleDisabled(config: JsonElement?): Boolean =
    config is JsonObject &&
        config[DISABLED_KEY]?.let {
            it is JsonPrimitive && it.content == "true"
        } == true
