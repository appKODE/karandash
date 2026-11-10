package io.karandash.sdk.config

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

@PublishedApi
internal val sdkConfigMapJson: Json = KarandashJson.ConfigEncoder

/**
 * Converts a [JsonElement] tree back to plain Kotlin types.
 *
 * - [JsonNull] → `null`
 * - [JsonPrimitive] → [String], [Boolean], [Int], [Long], or [Double]
 * - [JsonArray] → `List<Any?>`
 * - [JsonObject] → `Map<String, Any?>`
 */
public fun JsonElement.toAnyValue(): Any? =
    when (this) {
        is JsonNull -> null

        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> boolean
            intOrNull != null -> int
            longOrNull != null -> long
            doubleOrNull != null -> double
            else -> content
        }

        is JsonArray -> map { it.toAnyValue() }

        is JsonObject -> entries.associate { (k, v) -> k to v.toAnyValue() }
    }

/**
 * Serializes any [@Serializable][kotlinx.serialization.Serializable] config data class
 * to a [JsonElement] using the kotlinx.serialization JSON encoder.
 *
 * Default values are encoded; null fields are omitted.
 */
public inline fun <reified T : Any> T.toConfigJson(): JsonElement = sdkConfigMapJson.encodeToJsonElement(this)

/**
 * Shared [Json] instance for deserializing config [JsonElement] trees into
 * `@Serializable` config data classes via `decodeFromJsonElement<T>()`.
 *
 * Lenient settings tolerate minor format variations across YAML, JSON, and KTS parsers.
 */
public val configDeserializer: Json = KarandashJson.ConfigDecoder

/**
 * Converts a plain Kotlin value (as produced by YAML/JSON map parsers) to a [JsonElement].
 * Delegates to the canonical [io.karandash.api.support.anyToJsonElement].
 */
public fun anyToJsonElement(value: Any?): JsonElement =
    io.karandash.api.support
        .anyToJsonElement(value)

/**
 * Converts a [JsonElement] to a `Map<String, Any?>`.
 *
 * [JsonObject] elements are converted recursively via [toAnyValue].
 * Non-object elements return an empty map.
 *
 * Bridge and test boundary only (the JS/native plugin bridges hand plain maps to foreign code); config code
 * reads the [JsonElement] directly.
 */
public fun JsonElement.toAnyMap(): Map<String, Any?> =
    // parser-boundary: plain maps for JS/native bridges and tests
    when (this) {
        is JsonObject -> entries.associate { (k, v) -> k to v.toAnyValue() }
        else -> emptyMap()
    }
