package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.http.HttpAuth
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Parses the `extends[].auth` / manifest `auth` block (`type: bearer-token | basic-auth | custom-header`). */
object ExtendsAuthParser {
    private val authJson = KarandashJson.IgnoreUnknown // classDiscriminator "type" is the kotlinx default
    private val SUPPORTED_TYPES = setOf("bearer-token", "basic-auth", "custom-header")
    private val MISSING_FIELD = Regex("""Field '(\w+)' is required""")

    /**
     * Parse a [JsonObject] auth block into an [HttpAuth]. String values are interpolated before deserialization.
     *
     * @param data The auth [JsonObject] from config. Returns null if null.
     * @param missingFieldCode Bracket code for missing field errors.
     * @param invalidTypeCode Bracket code for invalid auth type errors.
     * @param interpolate Performs environment variable interpolation on a string value.
     */
    fun fromJsonObject(
        data: JsonObject?,
        missingFieldCode: String,
        invalidTypeCode: String,
        interpolate: (String) -> String,
    ): HttpAuth? {
        if (data == null) return null
        val interpolated = JsonObject(
            data.entries.associate { (k, v) ->
                k to if (k != "type" && v is JsonPrimitive && v.isString) JsonPrimitive(interpolate(v.content)) else v
            },
        )
        val type = (interpolated["type"] as? JsonPrimitive)?.content
            ?: throw ConfigException("$missingFieldCode Missing 'type' in auth")

        if (type.lowercase() !in SUPPORTED_TYPES) {
            throw ConfigException(
                "$invalidTypeCode Unknown auth type: '$type'. Supported types: bearer-token, basic-auth, custom-header",
            )
        }

        return try {
            authJson.decodeFromJsonElement(HttpAuth.serializer(), interpolated)
        } catch (e: SerializationException) {
            val fieldName = MISSING_FIELD.find(e.message.orEmpty())?.groupValues?.get(1)
                ?: throw ConfigException("$invalidTypeCode Invalid auth configuration for type '$type': ${e.message}")
            throw ConfigException("$missingFieldCode Missing required field '$fieldName' in auth")
        }
    }
}
