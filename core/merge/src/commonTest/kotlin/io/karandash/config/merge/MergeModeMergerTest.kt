package io.karandash.config.merge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MergeModeMergerTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun merge(
        base: String,
        override: String,
        strategy: MergeStrategyMap? = null,
    ) = MergeModeMerger.merge(json(base), json(override), strategy)

    @Test
    fun `objects merge deeply - child keys win - parent keys are kept - null clears`() {
        val merged = merge("""{"a":{"x":1,"y":2},"b":true,"c":"keep"}""", """{"a":{"y":3},"b":null}""")
        assertEquals(json("""{"a":{"x":1,"y":3},"b":null,"c":"keep"}"""), merged)
    }

    @Test
    fun `arrays replace by default and follow the per-path mode`() {
        val base = """{"labels":["a","b"],"nested":{"list":["a"]}}"""
        val override = """{"labels":["b","c"],"nested":{"list":["z"]}}"""
        assertEquals(json("""{"labels":["b","c"],"nested":{"list":["z"]}}"""), merge(base, override))
        val modes = mapOf(
            MergeMode.APPEND to """["a","b","b","c"]""",
            MergeMode.PREPEND to """["b","c","a","b"]""",
            MergeMode.MERGE to """["a","b","c"]""",
        )
        modes.forEach { (mode, expected) ->
            val merged = merge(base, override, mapOf("labels" to mode, "nested.list" to MergeMode.APPEND))
            assertEquals(json("""{"labels":$expected,"nested":{"list":["a","z"]}}"""), merged, mode.name)
        }
    }

    @Test
    fun `merge-by-key matches on name then when - keeps unmatched - appends new and unkeyed`() {
        val base = """{"p":[{"name":"a","v":1,"keep":true},{"when":"w","v":1},{"name":"old"}]}"""
        val override = """{"p":[{"name":"a","v":2},{"when":"w","v":2},{"name":"new"},"loose"]}"""
        val merged = merge(base, override, mapOf("p" to MergeMode.MERGE_BY_KEY))
        val expected = """{"p":[{"name":"a","v":2,"keep":true},{"when":"w","v":2},{"name":"old"},{"name":"new"},"loose"]}"""
        assertEquals(json(expected), merged)
    }

    @Test
    fun `merge-by-key items follow the strategy of their nested paths`() {
        val base = """{"policies":[{"name":"a","rules":["x"]}]}"""
        val override = """{"policies":[{"name":"a","rules":["y"]}]}"""
        val strategy = mapOf("policies" to MergeMode.MERGE_BY_KEY, "policies.rules" to MergeMode.APPEND)
        assertEquals(json("""{"policies":[{"name":"a","rules":["x","y"]}]}"""), merge(base, override, strategy))
    }

    @Test
    fun `fromString accepts kebab and any case and rejects unknown`() {
        assertEquals(MergeMode.MERGE_BY_KEY, MergeMode.fromString("merge-by-key"))
        assertEquals(MergeMode.APPEND, MergeMode.fromString("Append"))
        assertNull(MergeMode.fromString("concat"))
    }

    @Test
    fun `merge-by-key items without a key and non-object items are kept from base and override`() {
        val base = """{"p":["x",{"v":1},{"name":"a","list":[1]}]}"""
        val override = """{"p":[{"name":"a","list":[2]},{"v":2}]}"""
        val merged = merge(base, override, mapOf("p" to MergeMode.MERGE_BY_KEY))
        assertEquals(json("""{"p":["x",{"v":1},{"name":"a","list":[2]},{"v":2}]}"""), merged)
    }

    @Test
    fun `scalars and mismatched types follow the override and top level null clears`() {
        assertEquals(json("2"), merge("1", "2"))
        assertEquals(json("""{"a":[1]}"""), merge("""{"a":{"b":1}}""", """{"a":[1]}"""))
        assertEquals(json("null"), merge("""{"a":1}""", "null"))
    }

    @Test
    fun `append prepend and merge keep every element of both sides`() {
        val random = Random(3)
        repeat(100) {
            val base = List(random.nextInt(0, 5)) { random.nextInt(4) }
            val override = List(random.nextInt(0, 5)) { random.nextInt(4) }
            val b = json("""{"l":$base}""")
            val o = json("""{"l":$override}""")

            fun run(mode: MergeMode) = MergeModeMerger.merge(b, o, mapOf("l" to mode)).toString()
            assertEquals("""{"l":${(base + override).joinToString(",", "[", "]")}}""", run(MergeMode.APPEND))
            assertEquals("""{"l":${(override + base).joinToString(",", "[", "]")}}""", run(MergeMode.PREPEND))
            assertEquals("""{"l":${(base + override).distinct().joinToString(",", "[", "]")}}""", run(MergeMode.MERGE))
            assertEquals("""{"l":${override.joinToString(",", "[", "]")}}""", run(MergeMode.REPLACE))
        }
    }
}
