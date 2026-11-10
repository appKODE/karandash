package io.karandash.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PomskyCompilerTest {
    // --- PomskyResult construction ---

    @Test
    fun `PomskyResult success has non-null regex and null error`() {
        val result = PomskyResult(regex = "^hello$", error = null)
        assertNotNull(result.regex)
        assertNull(result.error)
    }

    @Test
    fun `PomskyResult failure has null regex and non-null error`() {
        val result = PomskyResult(regex = null, error = "compilation failed")
        assertNull(result.regex)
        assertNotNull(result.error)
    }

    @Test
    fun `PomskyResult defaults warnings to empty list`() {
        val result = PomskyResult(regex = ".*", error = null)
        assertTrue(result.warnings.isEmpty())
    }

    // --- PatternResolver with fake PomskyCompiler ---

    @Test
    fun `PatternResolver uses pomsky compiler when provided and compilation succeeds`() {
        val compiler = PomskyCompiler { PomskyResult(regex = "^feat$", error = null) }
        val resolver = PatternResolver(registry = EmptyPatternRegistry, pomskyCompiler = compiler)

        val result = resolver.resolve("pomsky:let t = 'feat'; ^ t $")
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(PatternType.POMSKY, result.type)
        assertTrue(result.regex.matches("feat"))
    }

    @Test
    fun `PatternResolver returns Error when pomsky compiler returns failure`() {
        val compiler = PomskyCompiler { PomskyResult(regex = null, error = "unknown token") }
        val resolver = PatternResolver(registry = EmptyPatternRegistry, pomskyCompiler = compiler)

        val result = resolver.resolve("pomsky:invalid @@@ expression")
        assertIs<PatternCompilationResult.Error>(result)
        assertTrue(result.message.contains("unknown token"))
    }

    @Test
    fun `PatternResolver returns Error with install hint when pomsky compiler is null`() {
        val resolver = PatternResolver(registry = EmptyPatternRegistry, pomskyCompiler = null)

        val result = resolver.resolve("pomsky:^ 'hello'")
        assertIs<PatternCompilationResult.Error>(result)
        assertTrue(result.message.contains("not available"))
    }

    @Test
    fun `pomsky pattern original pattern preserved in Success`() {
        val pattern = "pomsky:^ 'test' $"
        val compiler = PomskyCompiler { PomskyResult(regex = "^test$", error = null) }
        val resolver = PatternResolver(registry = EmptyPatternRegistry, pomskyCompiler = compiler)

        val result = resolver.resolve(pattern)
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(pattern, result.originalPattern)
    }

    @Test
    fun `pomsky pattern original pattern preserved in Error`() {
        val pattern = "pomsky:broken"
        val compiler = PomskyCompiler { PomskyResult(regex = null, error = "oops") }
        val resolver = PatternResolver(registry = EmptyPatternRegistry, pomskyCompiler = compiler)

        val result = resolver.resolve(pattern)
        assertIs<PatternCompilationResult.Error>(result)
        assertEquals(pattern, result.originalPattern)
    }

    @Test
    fun `pomsky result with invalid regex string returns Error`() {
        val compiler = PomskyCompiler { PomskyResult(regex = "[unclosed", error = null) }
        val resolver = PatternResolver(registry = EmptyPatternRegistry, pomskyCompiler = compiler)

        val result = resolver.resolve("pomsky:^ [unclosed")
        assertIs<PatternCompilationResult.Error>(result)
    }
}

/** Minimal no-op registry for tests that do not exercise named patterns. */
private object EmptyPatternRegistry : PatternRegistry {
    override fun register(definition: PatternDefinition) = Unit

    override fun resolve(
        name: String,
        params: Map<String, String>
    ): String? = null

    override fun exists(name: String): Boolean = false

    override fun all(): List<PatternDefinition> = emptyList()
}
