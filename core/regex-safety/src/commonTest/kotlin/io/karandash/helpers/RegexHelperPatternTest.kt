package io.karandash.helpers

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.PatternAwareCompiler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegexHelperPatternTest {
    @AfterTest
    fun tearDown() {
        RegexHelper.clearPatternCompiler()
    }

    // ------------------------------------------------------------------
    // Test 1: tryCompile delegates pattern syntax to injected compiler
    // ------------------------------------------------------------------

    @Test
    fun `tryCompile delegates alt-prefixed pattern to injected compiler`() {
        val compiledPatterns = mutableListOf<String>()
        val mockCompiler = PatternAwareCompiler { pattern, _, _ ->
            compiledPatterns.add(pattern)
            Regex(".*")
        }
        RegexHelper.injectPatternCompiler(mockCompiler)

        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("@test", "rule-id", findings)

        assertNotNull(result)
        assertTrue(compiledPatterns.contains("@test"), "Compiler should have been called with '@test'")
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `tryCompile delegates glob-prefixed pattern to injected compiler`() {
        val compiledPatterns = mutableListOf<String>()
        val mockCompiler = PatternAwareCompiler { pattern, _, _ ->
            compiledPatterns.add(pattern)
            Regex(".*")
        }
        RegexHelper.injectPatternCompiler(mockCompiler)

        val findings = mutableListOf<Finding>()
        RegexHelper.tryCompile("glob:{feat,fix}: *", "rule-id", findings)

        assertTrue(compiledPatterns.contains("glob:{feat,fix}: *"))
    }

    @Test
    fun `tryCompile delegates regex-prefixed pattern to injected compiler`() {
        val compiledPatterns = mutableListOf<String>()
        val mockCompiler = PatternAwareCompiler { pattern, _, _ ->
            compiledPatterns.add(pattern)
            Regex(".*")
        }
        RegexHelper.injectPatternCompiler(mockCompiler)

        val findings = mutableListOf<Finding>()
        RegexHelper.tryCompile("regex:^abc$", "rule-id", findings)

        assertTrue(compiledPatterns.contains("regex:^abc$"))
    }

    @Test
    fun `tryCompile delegates pomsky-prefixed pattern to injected compiler`() {
        val compiledPatterns = mutableListOf<String>()
        val mockCompiler = PatternAwareCompiler { pattern, _, _ ->
            compiledPatterns.add(pattern)
            Regex(".*")
        }
        RegexHelper.injectPatternCompiler(mockCompiler)

        val findings = mutableListOf<Finding>()
        RegexHelper.tryCompile("pomsky:['a'-'z']+", "rule-id", findings)

        assertTrue(compiledPatterns.contains("pomsky:['a'-'z']+"))
    }

    // ------------------------------------------------------------------
    // Test 2: tryCompile does NOT delegate bare regex to compiler
    // ------------------------------------------------------------------

    @Test
    fun `tryCompile compiles bare regex locally without calling injected compiler`() {
        val compiledPatterns = mutableListOf<String>()
        val mockCompiler = PatternAwareCompiler { pattern, _, _ ->
            compiledPatterns.add(pattern)
            Regex(".*")
        }
        RegexHelper.injectPatternCompiler(mockCompiler)

        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("^abc$", "rule-id", findings)

        assertNotNull(result)
        assertTrue(compiledPatterns.isEmpty(), "Bare regex should bypass the pattern compiler")
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `tryCompile compiles bare regex locally and matches expected string`() {
        val mockCompiler = PatternAwareCompiler { _, _, _ -> Regex("nomatch") }
        RegexHelper.injectPatternCompiler(mockCompiler)

        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("^hello$", "rule-id", findings)

        assertNotNull(result)
        assertTrue(result.matches("hello"))
        assertTrue(!result.matches("nomatch"))
    }

    // ------------------------------------------------------------------
    // Test 3: without compiler, pattern syntax falls through as bare regex
    // ------------------------------------------------------------------

    @Test
    fun `tryCompile without injected compiler treats alt-pattern as bare regex`() {
        // No compiler injected - @test is not valid regex and should fail
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("@test-pattern", "rule-id", findings)

        // @test-pattern is actually valid as a regex (@ is a literal char in regex),
        // so check the compiler was NOT needed: result may succeed as literal regex match.
        // The important invariant: no compiler was called (none injected), no crash.
        // Result is non-null because @test-pattern is a syntactically valid regex.
        assertNotNull(result, "@test-pattern is a valid regex literal when no compiler is present")
        assertTrue(findings.isEmpty(), "No error finding expected for syntactically valid pattern")
    }

    @Test
    fun `tryCompile without compiler treats invalid alt-pattern-syntax as bare regex and may fail`() {
        // A pattern that is invalid as a bare regex
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("@[invalid", "rule-id", findings)

        assertNull(result)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("rule-id", findings[0].rule)
    }

    // ------------------------------------------------------------------
    // Test 4: clearPatternCompiler removes the compiler
    // ------------------------------------------------------------------

    @Test
    fun `clearPatternCompiler removes injected compiler`() {
        val compiledPatterns = mutableListOf<String>()
        val mockCompiler = PatternAwareCompiler { pattern, _, _ ->
            compiledPatterns.add(pattern)
            Regex(".*")
        }
        RegexHelper.injectPatternCompiler(mockCompiler)

        // Verify compiler is active
        val findings1 = mutableListOf<Finding>()
        RegexHelper.tryCompile("@test", "rule-id", findings1)
        assertEquals(1, compiledPatterns.size, "Compiler should have been invoked before clear")

        // Clear and verify compiler no longer invoked
        RegexHelper.clearPatternCompiler()
        compiledPatterns.clear()

        val findings2 = mutableListOf<Finding>()
        RegexHelper.tryCompile("@test", "rule-id", findings2)
        assertTrue(compiledPatterns.isEmpty(), "Compiler should not be invoked after clear")
    }

    @Test
    fun `injectPatternCompiler then clearPatternCompiler resets to null`() {
        val mockCompiler = PatternAwareCompiler { _, _, _ -> Regex(".*") }
        RegexHelper.injectPatternCompiler(mockCompiler)

        RegexHelper.clearPatternCompiler()

        // After clear, patternCompiler should be null; bare regex still works
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("^abc$", "rule-id", findings)
        assertNotNull(result)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `overlapping runs resolve their own named patterns and a finished run clears only its compiler`() {
        fun runCompiler(
            name: String,
            regex: String
        ) = PatternAwareCompiler { pattern, rule, findings ->
            if (pattern == "@$name") {
                Regex(regex)
            } else {
                findings += Finding(severity = Severity.ERROR, message = "unknown $pattern", rule = rule)
                null
            }
        }
        val first = runCompiler("ticket", "^A-\\d+$")
        val second = runCompiler("scope", "^(core|cli)$")
        RegexHelper.injectPatternCompiler(first)
        RegexHelper.injectPatternCompiler(second)

        val findings = mutableListOf<Finding>()
        assertEquals("^A-\\d+$", RegexHelper.tryCompile("@ticket", "r", findings)?.pattern)
        assertEquals("^(core|cli)$", RegexHelper.tryCompile("@scope", "r", findings)?.pattern)
        assertTrue(findings.isEmpty(), "a run's name must not error just because a newer run is active")

        RegexHelper.clearPatternCompiler(second)
        assertEquals("^A-\\d+$", RegexHelper.tryCompile("@ticket", "r", findings)?.pattern)
        assertNull(RegexHelper.tryCompile("@scope", "r", findings))
        assertEquals(1, findings.size)
    }
}
