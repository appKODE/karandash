package io.karandash.patterns

import io.karandash.helpers.PatternPrefixes
import io.karandash.helpers.RegexHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatternTypeTest {
    @Test
    fun `type detection prefix stripping and pattern-syntax check agree on every prefix`() {
        for (prefix in PatternPrefixes.ALL) {
            val pattern = "${prefix}body"
            assertTrue(detectPatternType(pattern) != PatternType.BARE, pattern)
            assertTrue(RegexHelper.looksLikePatternSyntax(pattern), pattern)
            assertEquals("body", PatternPrefixes.strip(pattern))
        }
        assertEquals(PatternType.BARE, detectPatternType("body"))
        assertFalse(RegexHelper.looksLikePatternSyntax("body"))
    }

    @Test
    fun `named pattern detected by at-sign prefix`() {
        assertEquals(PatternType.NAMED, detectPatternType("@jira-ticket"))
    }

    @Test
    fun `named pattern with params detected`() {
        assertEquals(PatternType.NAMED, detectPatternType("@kebab-case"))
    }

    @Test
    fun `pomsky pattern detected by pomsky colon prefix`() {
        assertEquals(PatternType.POMSKY, detectPatternType("pomsky:let t = 'feat'; ^ t"))
    }

    @Test
    fun `glob pattern detected by glob colon prefix`() {
        assertEquals(PatternType.GLOB, detectPatternType("glob:{feat,fix}:*"))
    }

    @Test
    fun `regex pattern detected by regex colon prefix`() {
        assertEquals(PatternType.REGEX, detectPatternType("regex:^[A-Z]+-\\d+"))
    }

    @Test
    fun `bare pattern has no prefix`() {
        assertEquals(PatternType.BARE, detectPatternType("^[A-Z]+-\\d+"))
    }

    @Test
    fun `empty string is bare`() {
        assertEquals(PatternType.BARE, detectPatternType(""))
    }

    @Test
    fun `at sign alone is named`() {
        assertEquals(PatternType.NAMED, detectPatternType("@"))
    }

    @Test
    fun `pomsky prefix without content is pomsky`() {
        assertEquals(PatternType.POMSKY, detectPatternType("pomsky:"))
    }

    @Test
    fun `glob prefix without content is glob`() {
        assertEquals(PatternType.GLOB, detectPatternType("glob:"))
    }

    @Test
    fun `regex prefix without content is regex`() {
        assertEquals(PatternType.REGEX, detectPatternType("regex:"))
    }
}
