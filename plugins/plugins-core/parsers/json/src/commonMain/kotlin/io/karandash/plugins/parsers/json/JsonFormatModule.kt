package io.karandash.plugins.parsers.json

import io.karandash.api.module.ConfigFormatModule
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSON config format module using kotlinx-serialization.
 * Works on both JVM and Native platforms.
 */
class JsonFormatModule : ConfigFormatModule {
    companion object {
        const val FORMAT_ID = "json"
        const val PRIORITY = 20
    }

    override val formatId: String = FORMAT_ID
    override val fileExtensions: Set<String> = setOf("json")
    override val priority: Int = PRIORITY

    private val json = KarandashJson.ConfigFile

    /**
     * Parse JSON content into a [JsonElement] tree.
     *
     * @param content The JSON content as a string.
     * @return [JsonElement] representation of the JSON content.
     * @throws IllegalArgumentException if JSON is syntactically invalid.
     */
    override suspend fun parseToJsonElement(content: String): JsonElement {
        if (content.isBlank()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(content)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid JSON syntax: ${e.message}", e)
        }
    }
}
