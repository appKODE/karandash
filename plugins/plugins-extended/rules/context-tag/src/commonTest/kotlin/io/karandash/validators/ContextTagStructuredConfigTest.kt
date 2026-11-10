package io.karandash.validators

import io.karandash.patterns.DelimiterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextTagStructuredConfigTest {
    // ------------------------------------------------------------------
    // Test 1: delimiter BRACKETS generates correct pattern
    // ------------------------------------------------------------------

    @Test
    fun `delimiter brackets generates square-bracket wrapping pattern`() {
        val config = ContextTagConfig(delimiter = DelimiterType.BRACKETS)
        val pattern = config.effectiveTagPattern()

        val regex = Regex(pattern)
        assertTrue(regex.containsMatchIn("[ios]"), "Should match [ios]")
        assertTrue(regex.containsMatchIn("[android]"), "Should match [android]")
        assertFalse(regex.containsMatchIn("(ios)"), "Should not match (ios)")
        assertFalse(regex.containsMatchIn("{ios}"), "Should not match {ios}")
    }

    // ------------------------------------------------------------------
    // Test 2: delimiter PARENS generates correct pattern
    // ------------------------------------------------------------------

    @Test
    fun `delimiter parens generates parenthesis wrapping pattern`() {
        val config = ContextTagConfig(delimiter = DelimiterType.PARENS)
        val pattern = config.effectiveTagPattern()

        val regex = Regex(pattern)
        assertTrue(regex.containsMatchIn("(ios)"), "Should match (ios)")
        assertTrue(regex.containsMatchIn("(android)"), "Should match (android)")
        assertFalse(regex.containsMatchIn("[ios]"), "Should not match [ios]")
        assertFalse(regex.containsMatchIn("{ios}"), "Should not match {ios}")
    }

    // ------------------------------------------------------------------
    // Test 3: explicit tagPattern overrides delimiter
    // ------------------------------------------------------------------

    @Test
    fun `explicit tagPattern takes precedence over delimiter`() {
        val config = ContextTagConfig(
            tagPattern = "custom",
            delimiter = DelimiterType.PARENS,
        )
        val pattern = config.effectiveTagPattern()

        assertEquals("custom", pattern, "Explicit tagPattern should override delimiter")
    }

    // ------------------------------------------------------------------
    // Test 4: default with both null uses brackets
    // ------------------------------------------------------------------

    @Test
    fun `default config with no tagPattern and no delimiter uses brackets`() {
        val config = ContextTagConfig()
        val pattern = config.effectiveTagPattern()

        val regex = Regex(pattern)
        assertTrue(regex.containsMatchIn("[ios]"), "Default should match [ios] (brackets)")
        assertFalse(regex.containsMatchIn("(ios)"), "Default should not match (ios)")
    }

    // ------------------------------------------------------------------
    // Additional: delimiter BRACES generates correct pattern
    // ------------------------------------------------------------------

    @Test
    fun `delimiter braces generates curly-brace wrapping pattern`() {
        val config = ContextTagConfig(delimiter = DelimiterType.BRACES)
        val pattern = config.effectiveTagPattern()

        val regex = Regex(pattern)
        assertTrue(regex.containsMatchIn("{ios}"), "Should match {ios}")
        assertFalse(regex.containsMatchIn("[ios]"), "Should not match [ios]")
    }

    // ------------------------------------------------------------------
    // Additional: delimiter NONE matches bare identifier
    // ------------------------------------------------------------------

    @Test
    fun `delimiter none matches bare identifier without wrapping`() {
        val config = ContextTagConfig(delimiter = DelimiterType.NONE)
        val pattern = config.effectiveTagPattern()

        val regex = Regex(pattern)
        assertTrue(regex.containsMatchIn("ios"), "Should match bare 'ios'")
        // NONE delimiter still matches the identifier inside brackets - it just doesn't require them
        assertTrue(regex.containsMatchIn("[ios]"), "Should find 'ios' inside '[ios]' with none delimiter")
    }
}
