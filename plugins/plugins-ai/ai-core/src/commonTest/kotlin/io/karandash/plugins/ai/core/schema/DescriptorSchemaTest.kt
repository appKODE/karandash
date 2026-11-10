package io.karandash.plugins.ai.core.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DescriptorSchemaTest {
    @Serializable
    enum class Level { LOW, HIGH }

    @Serializable
    data class Item(
        val file: String,
        val line: Int? = null,
        val confidence: Double = 0.0,
        val level: Level = Level.LOW,
    )

    @Serializable
    data class Result(
        val items: List<Item> = emptyList(),
        val ok: Boolean,
    )

    @Test
    fun `class descriptor becomes an object schema with required non-default fields`() {
        val schema = JsonSchemaGenerator.fromDescriptor(Result.serializer().descriptor).jsonObject

        assertEquals("object", schema.getValue("type").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("ok"))), schema.getValue("required"))
        val items = schema
            .getValue("properties")
            .jsonObject
            .getValue("items")
            .jsonObject
        assertEquals("array", items.getValue("type").jsonPrimitive.content)
        val item = items.getValue("items").jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("file"))), item.getValue("required"))
        val props = item.getValue("properties").jsonObject
        assertEquals(
            "integer",
            props
                .getValue("line")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content
        )
        assertEquals(
            "number",
            props
                .getValue("confidence")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content
        )
        assertEquals(
            listOf("LOW", "HIGH"),
            props
                .getValue("level")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        )
    }
}
