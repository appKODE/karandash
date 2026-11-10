package io.karandash.plugins.ai.core.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generates JSON Schema objects from `@Serializable` class descriptors.
 *
 * The generated schema is suitable for sending to LLMs that support
 * `response_format: { type: "json_schema", schema: ... }`.
 */
object JsonSchemaGenerator {
    private const val TYPE = "type"
    private const val PROPERTIES = "properties"
    private const val REQUIRED = "required"
    private const val ENUM = "enum"
    private const val ITEMS = "items"

    private val TYPE_OBJECT = JsonPrimitive("object")
    private val TYPE_STRING = JsonPrimitive("string")
    private val TYPE_NUMBER = JsonPrimitive("number")
    private val TYPE_INTEGER = JsonPrimitive("integer")
    private val TYPE_BOOLEAN = JsonPrimitive("boolean")
    private val TYPE_ARRAY = JsonPrimitive("array")

    private const val MAX_DESCRIPTOR_DEPTH = 8

    /**
     * Derives a JSON Schema from a `@Serializable` class descriptor, so an analyzer can constrain
     * the model to exactly the shape its decoder reads. Fields with a default are optional;
     * nested classes and lists recurse up to a fixed depth, past which a property is left open.
     */
    fun fromDescriptor(descriptor: SerialDescriptor): JsonElement = descriptorSchema(descriptor, depth = 0)

    @OptIn(ExperimentalSerializationApi::class)
    private fun descriptorSchema(
        descriptor: SerialDescriptor,
        depth: Int,
    ): JsonObject {
        if (depth > MAX_DESCRIPTOR_DEPTH) return JsonObject(emptyMap())
        val kind = descriptor.kind
        return when (kind) {
            is PrimitiveKind -> primitiveSchema(kind)

            SerialKind.ENUM -> jsonObject(
                TYPE to TYPE_STRING,
                ENUM to toJsonArray(List(descriptor.elementsCount) { descriptor.getElementName(it) })
            )

            StructureKind.LIST -> jsonObject(TYPE to TYPE_ARRAY, ITEMS to descriptorSchema(descriptor.getElementDescriptor(0), depth + 1))

            StructureKind.CLASS, StructureKind.OBJECT -> classSchema(descriptor, depth)

            StructureKind.MAP, is PolymorphicKind, SerialKind.CONTEXTUAL -> jsonObject(TYPE to TYPE_OBJECT)
        }
    }

    private fun classSchema(
        descriptor: SerialDescriptor,
        depth: Int,
    ): JsonObject {
        val properties = descriptor.elementDescriptors
            .mapIndexed { index, element -> descriptor.getElementName(index) to descriptorSchema(element, depth + 1) }
            .toMap()
        val required = (0 until descriptor.elementsCount)
            .filterNot { descriptor.isElementOptional(it) }
            .map { descriptor.getElementName(it) }
        return jsonObject(TYPE to TYPE_OBJECT, PROPERTIES to JsonObject(properties), REQUIRED to toJsonArray(required))
    }

    private fun primitiveSchema(kind: PrimitiveKind): JsonObject =
        when (kind) {
            PrimitiveKind.BOOLEAN -> jsonObject(TYPE to TYPE_BOOLEAN)
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> jsonObject(TYPE to TYPE_INTEGER)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> jsonObject(TYPE to TYPE_NUMBER)
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> stringSchema()
        }

    private fun stringSchema(): JsonObject = jsonObject(TYPE to TYPE_STRING)

    private fun toJsonArray(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun jsonObject(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())
}
