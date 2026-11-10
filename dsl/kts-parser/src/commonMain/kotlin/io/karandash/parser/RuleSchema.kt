package io.karandash.parser

import io.karandash.api.exception.ConfigParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How a DSL field value should be evaluated from the AST expression. */
enum class FieldType { STRING, INT, BOOLEAN, STRING_LIST }

/**
 * Declarative schema for a rule block.
 *
 * @param fields Map of field name to evaluation type.
 * @param required Field names that must be present after interpretation (throws [ConfigParseException] if missing).
 * @param defaults Default values to apply when a field is absent.
 *
 * Not @Serializable: [defaults] holds untyped `Any` values.
 */
data class RuleSchema(
    val fields: Map<String, FieldType>,
    val required: Map<String, String> = emptyMap(),
    val defaults: Map<String, Any> = emptyMap(),
) {
    companion object {
        /**
         * Converts one rule's JSON Schema (`{"properties": {...}, "required": [...]}`) into a [RuleSchema].
         *
         * Property `type` maps `string` to STRING, `integer` to INT, `boolean` to BOOLEAN and
         * `array` of `string` items to STRING_LIST. Other types (objects, object arrays) are skipped
         * because the flat KTS DSL has no nested blocks inside rule declarations.
         * A property's `default` is kept as its [kotlinx.serialization.json.JsonElement].
         */
        fun fromJsonSchema(schema: JsonObject): RuleSchema {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val requiredNames = (schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
                .toSet()
            val fields = properties
                .mapNotNull { (name, prop) ->
                    (prop as? JsonObject)?.let(::fieldTypeOf)?.let { name to it }
                }.toMap()
            val required = requiredNames.filter { it in fields }.associateWith { "$it required" }
            val defaults = fields.keys
                .mapNotNull { name ->
                    (properties[name] as? JsonObject)?.get("default")?.let { name to it }
                }.toMap()
            return RuleSchema(fields, required, defaults)
        }

        private fun fieldTypeOf(property: JsonObject): FieldType? =
            when ((property["type"] as? JsonPrimitive)?.contentOrNull) {
                "string" -> {
                    FieldType.STRING
                }

                "integer" -> {
                    FieldType.INT
                }

                "boolean" -> {
                    FieldType.BOOLEAN
                }

                "array" -> {
                    val items = property["items"] as? JsonObject
                    FieldType.STRING_LIST.takeIf { (items?.get("type") as? JsonPrimitive)?.contentOrNull == "string" }
                }

                else -> {
                    null
                }
            }
    }
}
