package io.karandash.multipattern

import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class MultiPatternMatcherPropertyTest {
    private fun naive(
        patterns: List<String>,
        text: String,
    ): Set<PatternMatch> =
        patterns
            .filter { it.isNotEmpty() }
            .flatMap { pattern ->
                (0..text.length - pattern.length)
                    .filter { text.startsWith(pattern, it) }
                    .map { PatternMatch(pattern, it) }
            }.toSet()

    @Test
    fun `findAll reports exactly the occurrences a naive scan finds`() {
        val random = Random(11)
        repeat(300) {
            val patterns = List(random.nextInt(1, 6)) { word(random, 1, 4) }.distinct()
            val text = word(random, 0, 40)
            val matcher = MultiPatternMatcher.build(patterns)

            val found = matcher.findAll(text)

            assertEquals(naive(patterns, text), found.toSet(), "patterns=$patterns text=$text")
            assertEquals(found.size, found.toSet().size)
            assertEquals(found.isNotEmpty(), matcher.containsAny(text))
            assertEquals(patterns.size, matcher.patternCount())
        }
    }

    @Test
    fun `patterns that are suffixes of other patterns are counted once`() {
        assertEquals(2, MultiPatternMatcher.build(listOf("ab", "b")).patternCount())
        assertEquals(3, MultiPatternMatcher.build(listOf("abc", "bc", "c", "c")).patternCount())
    }

    @Test
    fun `an empty pattern list never matches`() {
        val matcher = MultiPatternMatcher.build(emptyList())

        assertEquals(0, matcher.patternCount())
        assertEquals(emptyList(), matcher.findAll("anything"))
        assertFalse(matcher.containsAny("anything"))
    }

    @Test
    fun `matches round trip through json and compare by value`() {
        val match = PatternMatch("abc", 3)
        val json = Json.encodeToString(PatternMatch.serializer(), match)

        assertEquals(match, Json.decodeFromString(PatternMatch.serializer(), json))
        assertNotEquals(match, match.copy(pattern = "abd"))
        assertNotEquals(match, match.copy(position = 4))
        assertEquals(match.hashCode(), match.copy().hashCode())
    }

    private fun word(
        random: Random,
        min: Int,
        max: Int,
    ): String = List(random.nextInt(min, max + 1)) { "abc"[random.nextInt(3)] }.joinToString("")
}
