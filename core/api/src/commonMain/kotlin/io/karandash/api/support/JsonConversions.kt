package io.karandash.api.support

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts a plain Kotlin value (as produced by YAML/JSON map parsers) to a [JsonElement].
 *
 * This is the canonical implementation; `io.karandash.sdk.anyToJsonElement` delegates here.
 */
public fun anyToJsonElement(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull

        is JsonElement -> value

        is String -> JsonPrimitive(value)

        is Boolean -> JsonPrimitive(value)

        is Int -> JsonPrimitive(value)

        is Long -> JsonPrimitive(value)

        is Double -> JsonPrimitive(value)

        is Float -> JsonPrimitive(value.toDouble())

        is Number -> JsonPrimitive(value.toLong())

        is Map<*, *> -> JsonObject(
            value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) },
        )

        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })

        is Collection<*> -> JsonArray(value.map { anyToJsonElement(it) })

        else -> JsonPrimitive(value.toString())
    }

/**
 * Converts a `Map<String, Any?>` (from YAML/JSON/KTS parsers) to a [JsonObject].
 *
 * This is the canonical implementation; `io.karandash.sdk.mapToJsonElement` delegates here.
 */
public fun mapToJsonElement(map: Map<String, Any?>): JsonObject =
    // parser-boundary: YAML/KTS parsers hand untyped maps here
    JsonObject(map.entries.associate { (k, v) -> k to anyToJsonElement(v) })
