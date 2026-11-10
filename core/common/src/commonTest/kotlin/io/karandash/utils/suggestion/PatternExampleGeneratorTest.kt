package io.karandash.utils.suggestion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternExampleGeneratorTest {
    @Test
    fun `generateExample extracts first alternative from simple alternation`() {
        val result = PatternExampleGenerator.generateExample("^(feat|fix):")
        assertNotNull(result)
        assertTrue(result.startsWith("feat:"), "Expected prefix 'feat:' but got: $result")
    }

    @Test
    fun `generateExample extracts first alternative from three-way alternation`() {
        val result = PatternExampleGenerator.generateExample("^(feat|fix|docs):")
        assertNotNull(result)
        assertTrue(result.startsWith("feat:"), "Expected prefix 'feat:' but got: $result")
    }

    @Test
    fun `generateExample returns null for empty pattern`() {
        assertNull(PatternExampleGenerator.generateExample(""))
    }

    @Test
    fun `generateExample returns null for blank pattern`() {
        assertNull(PatternExampleGenerator.generateExample("   "))
    }

    @Test
    fun `generateExample handles pattern with literal prefix`() {
        val result = PatternExampleGenerator.generateExample("^PROJ-")
        assertNotNull(result)
        assertTrue(result.startsWith("PROJ-"), "Expected prefix 'PROJ-' but got: $result")
    }

    @Test
    fun `generateExample returns null for complex character class`() {
        assertNull(PatternExampleGenerator.generateExample("[a-z]+"))
    }

    @Test
    fun `generateExample handles non-capturing group`() {
        val result = PatternExampleGenerator.generateExample("^(?:feat|fix):")
        assertNotNull(result)
        assertTrue(result.startsWith("feat:"), "Expected prefix 'feat:' but got: $result")
    }

    @Test
    fun `suggest returns example string for simple pattern`() {
        val result = PatternExampleGenerator.suggest("^(feat|fix):")
        assertNotNull(result)
        assertTrue(result.startsWith("Example: '"), "Expected 'Example:' prefix but got: $result")
    }

    @Test
    fun `suggest falls back to format hint for complex pattern`() {
        val result = PatternExampleGenerator.suggest("[A-Z]+-\\d+")
        assertNotNull(result)
        assertTrue(result.startsWith("Format:"), "Expected 'Format:' prefix but got: $result")
    }

    @Test
    fun `suggestFromGlobs produces example from first glob`() {
        val result = PatternExampleGenerator.suggestFromGlobs(listOf("feature/*", "bugfix/*"))
        assertNotNull(result)
        assertEquals("Example: 'feature/my-change'", result)
    }

    @Test
    fun `suggestFromGlobs returns null for empty list`() {
        assertNull(PatternExampleGenerator.suggestFromGlobs(emptyList()))
    }

    @Test
    fun `suggestFromGlobs replaces question mark with x`() {
        val result = PatternExampleGenerator.suggestFromGlobs(listOf("release-?"))
        assertNotNull(result)
        assertEquals("Example: 'release-x'", result)
    }

    @Test
    fun `generateFormatHint strips anchors`() {
        val result = PatternExampleGenerator.generateFormatHint("^(feat|fix):$")
        assertNotNull(result)
        assertEquals("Format: (feat|fix):", result)
    }

    @Test
    fun `generateFormatHint returns null for blank pattern`() {
        assertNull(PatternExampleGenerator.generateFormatHint(""))
    }

    @Test
    fun `generateExample handles escaped characters`() {
        val result = PatternExampleGenerator.generateExample("^\\[WIP\\]")
        assertNotNull(result)
        assertTrue(result.startsWith("[WIP]"), "Expected prefix '[WIP]' but got: $result")
    }

    @Test
    fun `generateExample stops at dot wildcard`() {
        val result = PatternExampleGenerator.generateExample("^feat:.*")
        assertNotNull(result)
        assertEquals("feat: ...", result)
    }
}
