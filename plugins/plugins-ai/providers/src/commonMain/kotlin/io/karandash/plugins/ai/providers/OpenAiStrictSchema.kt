package io.karandash.plugins.ai.providers

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenAI `strict: true` structured output: every object lists all its properties as required and sets
 * `additionalProperties: false`, so an optional property becomes `anyOf: [schema, null]`. [dropNulls] removes the
 * nulls this forces from the reply, so decoders see an optional field as absent, as they did before.
 */
internal object OpenAiStrictSchema {
    private const val TYPE = "type"
    private const val PROPERTIES = "properties"
    private const val REQUIRED = "required"
    private const val ITEMS = "items"
    private val SCALAR_TYPES = setOf("string", "number", "integer", "boolean")

    /**
     * The strict form of [schema], or null when it cannot be strict: OpenAI requires an object root, and every
     * part of it must have a strict form (no open object, no untyped items).
     */
    fun of(schema: JsonElement): JsonObject? =
        (schema as? JsonObject)?.takeIf { (it[TYPE] as? JsonPrimitive)?.contentOrNull == "object" }?.let(::strictNode)

    private fun strictNode(schema: JsonElement): JsonObject? {
        val node = schema as? JsonObject ?: return null
        return when ((node[TYPE] as? JsonPrimitive)?.contentOrNull) {
            "object" -> objectSchema(node)
            "array" -> node[ITEMS]?.let(::strictNode)?.let { JsonObject(node + (ITEMS to it)) }
            in SCALAR_TYPES -> node
            else -> null
        }
    }

    private fun objectSchema(node: JsonObject): JsonObject? {
        val properties = node[PROPERTIES] as? JsonObject
        if (properties.isNullOrEmpty()) return null
        val required = (node[REQUIRED] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        val strictProperties = properties.mapValues { (name, property) ->
            val strict = strictNode(property) ?: return null
            if (name in required) strict else nullable(strict)
        }
        return JsonObject(
            node + mapOf(
                PROPERTIES to JsonObject(strictProperties),
                REQUIRED to JsonArray(properties.keys.map(::JsonPrimitive)),
                "additionalProperties" to JsonPrimitive(false),
            ),
        )
    }

    private fun nullable(schema: JsonObject) =
        JsonObject(mapOf("anyOf" to JsonArray(listOf(schema, JsonObject(mapOf(TYPE to JsonPrimitive("null")))))))

    /** [content] without null-valued object entries; returned unchanged when it is not JSON. */
    fun dropNulls(content: String): String =
        try {
            withoutNulls(KarandashJson.Plain.parseToJsonElement(content)).toString()
        } catch (_: SerializationException) {
            content // not JSON: the analyzer's repair fallback handles it
        }

    private fun withoutNulls(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.filterValues { it != JsonNull }.mapValues { withoutNulls(it.value) })
            is JsonArray -> JsonArray(element.map(::withoutNulls))
            else -> element
        }
}
