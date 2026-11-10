package io.karandash.normalize

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** two keys that normalize to the same name are reported, not silently dropped. */
class DeepNormalizeKeysCollisionTest {
    @Test
    fun `colliding keys are reported with their original spellings`() {
        val input = buildJsonObject {
            put("title-format", JsonPrimitive("a"))
            put("titleFormat", JsonPrimitive("b"))
            put("other", JsonPrimitive("c"))
        }
        val collisions = mutableListOf<Pair<String, List<String>>>()

        deepNormalizeKeys(input) { normalized, originals -> collisions += normalized to originals }

        assertEquals(listOf("titleFormat" to listOf("title-format", "titleFormat")), collisions)
    }

    @Test
    fun `collision message names every spelling and the surviving key`() {
        assertEquals(
            "Config keys 'title-format', 'titleFormat' all normalize to 'titleFormat'; the last one wins",
            normalizedKeyCollisionMessage("titleFormat", listOf("title-format", "titleFormat")),
        )
    }

    @Test
    fun `preserved key holding an array keeps child keys and normalizes deeper keys`() {
        val input = buildJsonObject {
            put(
                "headers",
                JsonArray(
                    listOf(
                        buildJsonObject { put("X-Custom", buildJsonObject { put("max-age", JsonPrimitive(1)) }) },
                        JsonPrimitive("plain"),
                    ),
                ),
            )
        }

        val result = deepNormalizeKeys(input, preserveKeys = setOf("headers"))

        val expected = buildJsonObject {
            put(
                "headers",
                JsonArray(
                    listOf(
                        buildJsonObject { put("X-Custom", buildJsonObject { put("maxAge", JsonPrimitive(1)) }) },
                        JsonPrimitive("plain"),
                    ),
                ),
            )
        }
        assertEquals(expected, result)
    }
}
