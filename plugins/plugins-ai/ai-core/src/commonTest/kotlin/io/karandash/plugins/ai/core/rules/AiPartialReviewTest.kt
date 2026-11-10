package io.karandash.plugins.ai.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiPartialReviewTest {
    @Test
    fun `default maxFiles is 30`() {
        assertEquals(AiPartialReviewPolicy.DEFAULT_PARTIAL_MAX_FILES, AiPartialReviewPolicy().maxFiles)
    }

    @Test
    fun `default strategy is MOST_CHANGED`() {
        assertEquals(PartialReviewStrategy.MOST_CHANGED, AiPartialReviewPolicy().strategy)
    }

    @Test
    fun `PartialReviewStrategy fromString parses most-changed`() {
        assertEquals(PartialReviewStrategy.MOST_CHANGED, PartialReviewStrategy.fromString("most-changed"))
        assertEquals(PartialReviewStrategy.MOST_CHANGED, PartialReviewStrategy.fromString("MOST_CHANGED"))
        assertEquals(PartialReviewStrategy.MOST_CHANGED, PartialReviewStrategy.fromString("most_changed"))
    }

    @Test
    fun `PartialReviewStrategy fromString parses first`() {
        assertEquals(PartialReviewStrategy.FIRST, PartialReviewStrategy.fromString("first"))
        assertEquals(PartialReviewStrategy.FIRST, PartialReviewStrategy.fromString("FIRST"))
    }

    @Test
    fun `PartialReviewStrategy fromString parses by-type`() {
        assertEquals(PartialReviewStrategy.BY_TYPE, PartialReviewStrategy.fromString("by-type"))
        assertEquals(PartialReviewStrategy.BY_TYPE, PartialReviewStrategy.fromString("BY_TYPE"))
        assertEquals(PartialReviewStrategy.BY_TYPE, PartialReviewStrategy.fromString("by_type"))
    }

    @Test
    fun `PartialReviewStrategy fromString defaults to MOST_CHANGED for unknown values`() {
        assertEquals(PartialReviewStrategy.MOST_CHANGED, PartialReviewStrategy.fromString("unknown"))
        assertEquals(PartialReviewStrategy.MOST_CHANGED, PartialReviewStrategy.fromString(""))
    }

    @Test
    fun `policy preserves prioritize types`() {
        val types = listOf("*.kt", "*.java")
        val policy = AiPartialReviewPolicy(prioritizeTypes = types)
        assertEquals(types, policy.prioritizeTypes)
    }

    @Test
    fun `default exclude policy has sensible patterns`() {
        val patterns = AiExcludePolicy.DEFAULT_PATTERNS
        assertTrue(patterns.contains("**/generated/**"))
        assertTrue(patterns.contains("**/package-lock.json"))
        assertTrue(patterns.contains("**/*.min.js"))
        assertTrue(patterns.contains("**/*.min.css"))
    }

    @Test
    fun `exclude policy preserves custom patterns`() {
        val custom = listOf("**/build/**", "**/dist/**")
        val policy = AiExcludePolicy(patterns = custom)
        assertEquals(custom, policy.patterns)
    }
}
