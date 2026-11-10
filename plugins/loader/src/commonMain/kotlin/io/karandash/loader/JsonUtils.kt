package io.karandash.loader

import io.karandash.sdk.config.toAnyValue
import kotlinx.serialization.json.JsonElement

/**
 * Shared utilities for converting Kotlin types to kotlinx.serialization JSON elements.
 * Used by native and JS plugin bridges for config serialization.
 *
 * Delegates to the canonical implementations in [io.karandash.sdk.config.ConfigMapSerializer].
 */
internal object JsonUtils {
    /**
     * Convert any Kotlin type to JsonElement.
     * Handles primitives, collections, and nested structures.
     */
    fun anyToJsonElement(value: Any?): JsonElement =
        io.karandash.sdk.config
            .anyToJsonElement(value)

    /**
     * Convert a [JsonElement] to a Kotlin type.
     */
    fun jsonElementToAny(element: JsonElement): Any? = element.toAnyValue()
}
