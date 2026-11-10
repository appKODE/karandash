package io.karandash.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MultipatternRegressionTest {
    private val resolver = PatternResolver(registry = CorePatternRegistry.create(), pomskyCompiler = null)

    @Test
    fun `email pattern resolves end to end`() {
        val result = resolver.resolve("@email")
        assertIs<PatternCompilationResult.Success>(result, result.toString())
        assertTrue(result.regex.matches("user@example.com"))
        assertFalse(result.regex.matches("not-an-email"))
    }

    @Test
    fun `at sign not followed by a letter stays literal`() {
        val registry = CorePatternRegistry.create()
        assertEquals("a@1 b@", NamedPatternExpander.resolveInnerReferences(registry, "a@1 b@", emptySet(), 0))
    }
}
