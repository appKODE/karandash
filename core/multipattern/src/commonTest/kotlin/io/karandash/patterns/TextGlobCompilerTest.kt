package io.karandash.patterns

import io.karandash.cache.TieredCache
import io.karandash.helpers.SafeRegexCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextGlobCompilerTest {
    private val compiler = TextGlobCompiler()

    @Test
    fun `glob compiles through the injected regex compiler`() {
        val cache = TieredCache<String, Regex>(name = "glob-test", maxEntries = 8)
        val result = TextGlobCompiler(SafeRegexCompiler(cache)).compile("feat:*")
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(1, cache.size())
    }

    // --- success cases ---

    @Test
    fun `star wildcard matches any sequence`() {
        val result = compiler.compile("feat:*")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("feat:add login"))
        assertTrue(result.regex.matches("feat:"))
    }

    @Test
    fun `question mark matches single character`() {
        val result = compiler.compile("f?at")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("feat"))
        assertTrue(result.regex.matches("faat"))
    }

    @Test
    fun `alternation with braces matches any branch`() {
        val result = compiler.compile("{feat,fix,chore}:*")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("feat:something"))
        assertTrue(result.regex.matches("fix:something"))
        assertTrue(result.regex.matches("chore:something"))
    }

    @Test
    fun `alternation does not match unlisted branch`() {
        val result = compiler.compile("{feat,fix}:*")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(!result.regex.matches("docs:something"))
    }

    @Test
    fun `character class is passed through`() {
        val result = compiler.compile("[abc]at")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("aat"))
        assertTrue(result.regex.matches("bat"))
        assertTrue(!result.regex.matches("dat"))
    }

    @Test
    fun `regex is auto-anchored`() {
        // The leading ^ prevents a match when the string has a prefix before the pattern.
        val result = compiler.compile("feat")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(!result.regex.matches("prefix feat"))
        assertTrue(!result.regex.matches("feat suffix"))
    }

    @Test
    fun `literal dot is escaped`() {
        val result = compiler.compile("v1.0.0")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("v1.0.0"))
        assertTrue(!result.regex.matches("v1X0Y0"))
    }

    @Test
    fun `empty glob matches empty string only`() {
        val result = compiler.compile("")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches(""))
        assertTrue(!result.regex.matches("a"))
    }

    @Test
    fun `result type is GLOB`() {
        val result = compiler.compile("*")
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(PatternType.GLOB, result.type)
    }

    @Test
    fun `original pattern preserved in result`() {
        val glob = "feat:*"
        val result = compiler.compile(glob)
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(glob, result.originalPattern)
    }

    @Test
    fun `unclosed brace is treated as literal`() {
        val result = compiler.compile("{feat")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("{feat"))
    }

    @Test
    fun `unclosed bracket is treated as literal`() {
        val result = compiler.compile("[abc")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("[abc"))
    }

    // --- negation helpers ---

    @Test
    fun `isNegation returns true for bang-prefixed pattern`() {
        assertTrue(compiler.isNegation("!*.log"))
    }

    @Test
    fun `isNegation returns false for normal pattern`() {
        assertTrue(!compiler.isNegation("*.log"))
    }

    @Test
    fun `stripNegation removes leading bang`() {
        assertEquals("*.log", compiler.stripNegation("!*.log"))
    }

    @Test
    fun `stripNegation returns unchanged string without bang`() {
        assertEquals("*.log", compiler.stripNegation("*.log"))
    }

    /** Wildcards work inside brace branches. */
    @Test
    fun `brace branches keep wildcards`() {
        val result = compiler.compile("src/{*.kt,docs/*.md}")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("src/Main.kt"))
        assertTrue(result.regex.matches("src/docs/a.md"))
        assertTrue(!result.regex.matches("src/Main.java"))
    }

    /** Braces nest. */
    @Test
    fun `nested braces expand`() {
        val result = compiler.compile("{a,b{1,2}}.txt")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("a.txt"))
        assertTrue(result.regex.matches("b1.txt"))
        assertTrue(result.regex.matches("b2.txt"))
        assertTrue(!result.regex.matches("b3.txt"))
        assertTrue(!result.regex.matches("b.txt"))
    }

    /** `[!x]` negates a character class like in shell globs. */
    @Test
    fun `bang negates a character class`() {
        val result = compiler.compile("file[!0-9].txt")
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("fileA.txt"))
        assertTrue(!result.regex.matches("file5.txt"))
    }

    @Test
    fun `negated pattern compiles after stripping`() {
        val pattern = "!*.log"
        assertTrue(compiler.isNegation(pattern))
        val stripped = compiler.stripNegation(pattern)
        val result = compiler.compile(stripped)
        assertIs<PatternCompilationResult.Success>(result)
        assertTrue(result.regex.matches("error.log"))
        assertTrue(!result.regex.matches("error.txt"))
    }
}
