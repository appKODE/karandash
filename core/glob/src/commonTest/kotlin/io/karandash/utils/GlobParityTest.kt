package io.karandash.utils

import io.karandash.patterns.PatternCompilationResult
import io.karandash.patterns.TextGlobCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Freezes how each glob dialect matches today, so routing all three through one [GlobTranslator] cannot shift a
 * result: path-aware ([GlobMatcher]), wildcard-only ([GlobMatcher.wildcardMatches]) and text
 * ([TextGlobCompiler], braces and classes).
 */
class GlobParityTest {
    @Test
    fun `path mode results are frozen`() {
        assertCases("path", PATH_CASES) { pattern, input -> GlobMatcher.matches(input, pattern) }
    }

    @Test
    fun `codeowners mode results are frozen`() {
        assertCases("codeowners", CODEOWNERS_CASES) { pattern, input -> GlobMatcher.codeownersMatches(input, pattern) }
    }

    @Test
    fun `wildcard mode results are frozen`() {
        assertCases("wildcard", WILDCARD_CASES) { pattern, input -> GlobMatcher.wildcardMatches(input, pattern) }
        assertCases("wildcard ignore case", WILDCARD_IGNORE_CASE_CASES) { pattern, input ->
            GlobMatcher.wildcardMatches(input, pattern, ignoreCase = true)
        }
    }

    @Test
    fun `text mode results are frozen`() {
        val compiler = TextGlobCompiler()
        assertCases("text", TEXT_CASES) { pattern, input ->
            when (val result = compiler.compile(pattern)) {
                is PatternCompilationResult.Success -> result.regex.matches(input)
                is PatternCompilationResult.Error -> fail("$pattern did not compile: ${result.message}")
            }
        }
    }

    private fun assertCases(
        mode: String,
        cases: List<Triple<String, String, Boolean>>,
        matcher: (pattern: String, input: String) -> Boolean,
    ) {
        val wrong = cases.filter { (pattern, input, expected) -> matcher(pattern, input) != expected }
        assertEquals(emptyList(), wrong, "$mode glob results drifted")
    }

    private companion object {
        val PATH_CASES = listOf(
            Triple("*.kt", "Foo.kt", true),
            Triple("*.kt", "src/Foo.kt", false),
            Triple("**/*.kt", "Foo.kt", true),
            Triple("**/*.kt", "a/b/Foo.kt", true),
            Triple("src/**", "src", true),
            Triple("src/**", "src/a/b", true),
            Triple("src/**", "srcx", false),
            Triple("src/**/test/*.kt", "src/test/A.kt", true),
            Triple("src/**/test/*.kt", "src/x/y/test/A.kt", true),
            Triple("a?c", "abc", true),
            Triple("a?c", "a/c", false),
            Triple("a**b", "a/x/b", true),
            Triple("***", "a/b", true),
            Triple("file[1].txt", "file[1].txt", true),
            Triple("file[1].txt", "file1.txt", false),
            Triple("{a,b}.kt", "a.kt", false),
            Triple("{a,b}.kt", "{a,b}.kt", true),
            Triple("a+b(c)|d$^", "a+b(c)|d$^", true),
            Triple("a.b", "axb", false),
            Triple("a\\b", "a\\b", true),
        )

        val CODEOWNERS_CASES = listOf(
            Triple("/docs/", "docs/a/b.md", true),
            Triple("/docs/", "x/docs/a", false),
            Triple("docs/", "x/docs/y", true),
            Triple("*.js", "a/b/c.js", true),
            Triple("/build.gradle.kts", "/build.gradle.kts", true),
            Triple("/build.gradle.kts", "app/build.gradle.kts", false),
        )

        val WILDCARD_CASES = listOf(
            Triple("feat*", "feature", true),
            Triple("a/*", "a/b/c", true),
            Triple("v?", "v1", true),
            Triple("v?", "v12", false),
            Triple("v?", "v/", true),
            Triple("a.b*", "axb", false),
            Triple("[x]*", "[x]y", true),
            Triple("{a}*", "{a}b", true),
            Triple("a+b*", "a+bc", true),
            Triple("a(b)|c$^*", "a(b)|c$^", true),
            Triple("a\\*", "a\\z", true),
            Triple("BUG-*", "bug-1", false),
            Triple("Exact", "exact", false),
            Triple("Exact", "Exact", true),
        )

        val WILDCARD_IGNORE_CASE_CASES = listOf(
            Triple("BUG-*", "bug-1", true),
            Triple("Exact", "exact", true),
            Triple("v?", "V1", true),
        )

        val TEXT_CASES = listOf(
            Triple("*.kt", "a/b.kt", true),
            Triple("?", "ab", false),
            Triple("?", "/", true),
            Triple("{feat,fix}/*", "fix/x", true),
            Triple("{feat,fix}/*", "chore/x", false),
            Triple("{a,{b,c}d}", "cd", true),
            Triple("{a,{b,c}d}", "c", false),
            Triple("{a,*.kt}", "x.kt", true),
            Triple("[abc]x", "bx", true),
            Triple("[!abc]x", "bx", false),
            Triple("[!abc]x", "dx", true),
            Triple("[]x", "[]x", true),
            Triple("{a,b", "{a,b", true),
            Triple("a.b", "axb", false),
            Triple("a+b(c)|d$^", "a+b(c)|d$^", true),
        )
    }
}
