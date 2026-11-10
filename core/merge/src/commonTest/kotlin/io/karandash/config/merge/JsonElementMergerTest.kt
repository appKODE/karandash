package io.karandash.config.merge

import io.karandash.config.MergeStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonElementMergerTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun merge(
        base: String,
        override: String,
        resolver: MatchKeyResolver = MatchKeyResolver.DEFAULT,
    ) = JsonElementMerger.merge(json(base), json(override), resolver)

    @Test
    fun `objects merge deeply and explicit null removes the key`() {
        assertEquals(
            json("""{"a":{"x":1,"y":3,"z":4},"c":"keep"}"""),
            merge("""{"a":{"x":1,"y":2},"b":true,"c":"keep"}""", """{"a":{"y":3,"z":4},"b":null}"""),
        )
    }

    @Test
    fun `scalars and mismatched types are replaced by the override`() {
        assertEquals(json("2"), merge("1", "2"))
        assertEquals(json("""{"a":[1]}"""), merge("""{"a":{"b":1}}""", """{"a":[1]}"""))
        assertEquals(json("""{"a":{"b":1}}"""), merge("""{"a":[1]}""", """{"a":{"b":1}}"""))
        assertEquals(JsonNull, merge("""{"a":1}""", "null"))
    }

    @Test
    fun `object lists merge by name - matched items deep merge - new items append in order`() {
        val base = """{"s":[{"name":"a","v":1,"keep":true},{"name":"b","v":1}]}"""
        val override = """{"s":[{"name":"c","v":3},{"name":"a","v":2}]}"""

        assertEquals(
            json("""{"s":[{"name":"a","v":2,"keep":true},{"name":"b","v":1},{"name":"c","v":3}]}"""),
            merge(base, override),
        )
    }

    @Test
    fun `nested lists inside matched items merge by key with the nested path`() {
        val paths = mutableListOf<String>()
        val resolver = MatchKeyResolver { path ->
            paths += path
            "id"
        }
        val base = """{"s":[{"id":"a","items":[{"id":"x","v":1}]}]}"""
        val override = """{"s":[{"id":"a","items":[{"id":"y","v":2}]}]}"""

        assertEquals(
            json("""{"s":[{"id":"a","items":[{"id":"x","v":1},{"id":"y","v":2}]}]}"""),
            merge(base, override, resolver),
        )
        assertEquals(listOf("s", "s.items"), paths)
    }

    @Test
    fun `lists replace when the resolver opts out or items are not uniformly keyed`() {
        val replaced = """{"s":[{"name":"b"}]}"""
        val cases = listOf(
            Triple("""{"s":[{"name":"a"}]}""", replaced, MatchKeyResolver { null }),
            Triple("""{"s":["a"]}""", replaced, MatchKeyResolver.DEFAULT),
            Triple("""{"s":[{"name":"a"}]}""", """{"s":[{"name":"b"},"x"]}""", MatchKeyResolver.DEFAULT),
            Triple("""{"s":[{"other":"a"}]}""", replaced, MatchKeyResolver.DEFAULT),
            Triple("""{"s":[{"name":"a"}]}""", """{"s":[{"name":{"nested":1}}]}""", MatchKeyResolver.DEFAULT),
            Triple("""{"s":[{"name":null}]}""", replaced, MatchKeyResolver.DEFAULT),
        )
        cases.forEach { (base, override, resolver) ->
            assertEquals(json(override), merge(base, override, resolver), "$base + $override")
        }
    }

    @Test
    fun `non string primitive keys still match by content`() {
        assertEquals(
            json("""{"s":[{"name":1,"v":2,"k":true}]}"""),
            merge("""{"s":[{"name":1,"v":1,"k":true}]}""", """{"s":[{"name":1,"v":2}]}"""),
        )
    }

    @Test
    fun `merging with an empty object is the identity in both directions`() {
        val random = Random(7)
        repeat(100) {
            val value = randomObject(random, depth = 3)
            assertEquals(value, JsonElementMerger.merge(value, JsonObject(emptyMap())))
            assertEquals(value, JsonElementMerger.merge(JsonObject(emptyMap()), value))
            assertEquals(value, JsonElementMerger.merge(value, value))
        }
    }

    @Test
    fun `every override leaf is present in the merge result`() {
        val random = Random(11)
        repeat(100) {
            val base = randomObject(random, depth = 3)
            val override = randomObject(random, depth = 3)
            val merged = JsonElementMerger.merge(base, override) as JsonObject
            assertContainsLeaves(override, merged)
        }
    }

    private fun assertContainsLeaves(
        expected: JsonObject,
        actual: JsonObject,
    ) {
        expected.forEach { (key, value) ->
            val got = actual[key]
            when (value) {
                is JsonObject -> assertContainsLeaves(value, got as JsonObject)
                is JsonArray -> Unit
                else -> assertEquals(value, got, key)
            }
        }
    }

    private fun randomObject(
        random: Random,
        depth: Int,
    ): JsonObject =
        JsonObject(
            (0 until random.nextInt(0, 4)).associate { i ->
                val key = "k${random.nextInt(0, 3)}$i"
                key to if (depth > 0 && random.nextBoolean()) randomObject(random, depth - 1) else JsonPrimitive(random.nextInt(5))
            },
        )

    @Test
    fun `a top-level shallow merge replaces sections and replace drops the base`() {
        val base = json("""{"a": {"x": 1, "y": 2}, "b": 1}""")
        val override = json("""{"a": {"x": 3}}""")
        assertEquals(json("""{"a": {"x": 3}, "b": 1}"""), JsonElementMerger.mergeLayers(base, override, topLevel = MergeStrategy.SHALLOW))
        assertEquals(override, JsonElementMerger.mergeLayers(base, override, topLevel = MergeStrategy.REPLACE))
        assertEquals(override, JsonElementMerger.mergeLayers(JsonPrimitive(1), override, topLevel = MergeStrategy.SHALLOW))
        assertEquals(
            json("""{"a": {"x": 3, "y": 2}, "b": 1}"""),
            JsonElementMerger.mergeLayers(base, override, topLevel = MergeStrategy.DEEP),
        )
    }
}
