package io.karandash.plugins.ai.core.performance

import io.karandash.multipattern.MultiPatternMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiPatternMatcherTest {
    @Test
    fun `empty patterns matches nothing`() {
        val matcher = MultiPatternMatcher.build(emptyList())

        assertTrue(matcher.findAll("any text here").isEmpty())
        assertFalse(matcher.containsAny("any text here"))
    }

    @Test
    fun `single pattern found at correct position`() {
        val matcher = MultiPatternMatcher.build(listOf("hello"))

        val matches = matcher.findAll("say hello world")

        assertEquals(1, matches.size)
        assertEquals("hello", matches[0].pattern)
        assertEquals(4, matches[0].position)
    }

    @Test
    fun `single pattern not in text returns empty`() {
        val matcher = MultiPatternMatcher.build(listOf("missing"))

        assertTrue(matcher.findAll("text without the word").isEmpty())
    }

    @Test
    fun `multiple patterns found in single pass`() {
        val matcher = MultiPatternMatcher.build(listOf("password", "secret", "api_key"))

        val matches = matcher.findAll("my password is a secret and api_key too")

        val patterns = matches.map { it.pattern }.toSet()
        assertTrue(patterns.contains("password"))
        assertTrue(patterns.contains("secret"))
        assertTrue(patterns.contains("api_key"))
    }

    @Test
    fun `containsAny returns true when match exists`() {
        val matcher = MultiPatternMatcher.build(listOf("token", "key"))

        assertTrue(matcher.containsAny("the token is here"))
    }

    @Test
    fun `containsAny returns false when no match exists`() {
        val matcher = MultiPatternMatcher.build(listOf("token", "key"))

        assertFalse(matcher.containsAny("no sensitive data here"))
    }

    @Test
    fun `overlapping patterns both found`() {
        val matcher = MultiPatternMatcher.build(listOf("he", "she", "his", "hers"))

        val matches = matcher.findAll("ushers")
        val patterns = matches.map { it.pattern }.toSet()

        assertTrue(patterns.contains("she"))
        assertTrue(patterns.contains("he"))
        assertTrue(patterns.contains("hers"))
    }

    @Test
    fun `pattern not in text is not matched`() {
        val matcher = MultiPatternMatcher.build(listOf("alpha", "beta", "gamma"))

        val matches = matcher.findAll("delta epsilon zeta")

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `patternCount returns correct count`() {
        val matcher = MultiPatternMatcher.build(listOf("a", "b", "c", "d"))

        assertEquals(4, matcher.patternCount())
    }

    @Test
    fun `patternCount is zero for empty patterns`() {
        val matcher = MultiPatternMatcher.build(emptyList())

        assertEquals(0, matcher.patternCount())
    }

    @Test
    fun `pattern found at start of text`() {
        val matcher = MultiPatternMatcher.build(listOf("start"))

        val matches = matcher.findAll("start of the string")

        assertEquals(1, matches.size)
        assertEquals(0, matches[0].position)
    }

    @Test
    fun `pattern found at end of text`() {
        val matcher = MultiPatternMatcher.build(listOf("end"))

        val matches = matcher.findAll("this is the end")

        assertEquals(1, matches.size)
        assertEquals(12, matches[0].position)
    }

    @Test
    fun `same pattern found multiple times`() {
        val matcher = MultiPatternMatcher.build(listOf("ab"))

        val matches = matcher.findAll("ababab")

        assertEquals(3, matches.size)
        assertEquals(listOf(0, 2, 4), matches.map { it.position })
    }

    @Test
    fun `large text with many patterns completes correctly`() {
        val patterns = (1..15).map { "pattern$it" }
        val matcher = MultiPatternMatcher.build(patterns)
        val text = (1..100).joinToString(" ") { "word$it pattern${(it % 15) + 1} filler" }

        val matches = matcher.findAll(text)

        assertTrue(matches.isNotEmpty())
        // All found patterns must be from the original list
        assertTrue(matches.all { it.pattern in patterns })
    }

    @Test
    fun `match position is correct for multiple patterns`() {
        val matcher = MultiPatternMatcher.build(listOf("foo", "bar"))

        val matches = matcher.findAll("foo and bar")
        val fooMatch = matches.find { it.pattern == "foo" }
        val barMatch = matches.find { it.pattern == "bar" }

        assertEquals(0, fooMatch?.position)
        assertEquals(8, barMatch?.position)
    }
}
