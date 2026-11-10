package io.karandash.providers.common

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Shared steps of the platform `*Credentials.resolve` functions. */
object CredentialsSupport {
    /** [config] decoded with [serializer] when it is an object, else [default] (a module configured with no block). */
    fun <C> decodeOrDefault(
        config: JsonElement,
        serializer: DeserializationStrategy<C>,
        default: () -> C,
    ): C = if (config is JsonObject) KarandashJson.Default.decodeFromJsonElement(serializer, config) else default()

    /**
     * [configValue] when it is set and not blank, else the first of [envVars] that [env] reads as set and not blank.
     * A blank value (`GITLAB_TOKEN=""` from an unset CI secret) counts as unset, so the next source is tried.
     */
    fun firstNonBlank(
        configValue: String?,
        env: (String) -> String?,
        vararg envVars: String,
    ): String? =
        configValue?.takeIf { it.isNotBlank() }
            ?: envVars.firstNotNullOfOrNull { name -> env(name)?.takeIf { it.isNotBlank() } }
}
