package io.karandash.config.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * Decodes one config block; a bad value (a serializer's `error()`, a type mismatch) becomes a
 * [ConfigParseException] naming the block instead of an internal error.
 */
internal fun <T> decodeConfigSection(
    block: String,
    serializer: KSerializer<T>,
    data: JsonElement,
): T =
    try {
        KarandashJson.Default.decodeFromJsonElement(serializer, data)
    } catch (e: IllegalStateException) {
        throw ConfigParseException("'$block' is invalid: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw ConfigParseException("'$block' is invalid: ${e.message}")
    }
