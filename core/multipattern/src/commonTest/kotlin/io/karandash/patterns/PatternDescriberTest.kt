package io.karandash.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternDescriberTest {
    @Test
    fun titleFormatPattern() {
        val raw = "^((\\[PROJ-\\d+\\])+)?(\\[(ios|android|core)\\]) .+"
        val described = PatternDescriber.describe(raw)
        assertNotEquals(raw, described)
    }

    @Test
    fun conventionalCommit() {
        val raw = "^(feat|fix|docs|style|refactor|test|chore)(\\(.+\\))?: .{1,72}$"
        val described = PatternDescriber.describe(raw)
        assertNotEquals(raw, described)
    }

    @Test
    fun ticketPrefix() {
        val raw = "^[A-Z]{2,4}-\\d+: .+"
        val described = PatternDescriber.describe(raw)
        assertNotEquals(raw, described)
    }

    @Test
    fun pomskyPassthrough() {
        val raw = "pomsky:^ 'hello' [digit]+"
        val described = PatternDescriber.describe(raw)
        assertEquals("^ 'hello' [digit]+", described)
    }

    @Test
    fun globPassthrough() {
        val raw = "glob:{feat,fix}:*"
        val described = PatternDescriber.describe(raw)
        assertEquals(raw, described)
    }

    @Test
    fun namedPatternPassthrough() {
        val raw = "@conventional-commit"
        val described = PatternDescriber.describe(raw)
        assertEquals(raw, described)
    }

    // -- explain() tests --

    @Test
    fun explainBareRegexReturnsEnglish() {
        val explanation = PatternDescriber.explain("^[A-Z]{2}\\d{4}$")
        assertNotNull(explanation)
        assertTrue(explanation.isNotBlank(), "Explanation should not be blank")
    }

    @Test
    fun explainRegexPrefixReturnsEnglish() {
        val explanation = PatternDescriber.explain("regex:^[a-z]+$")
        assertNotNull(explanation)
        assertTrue(explanation.isNotBlank())
    }

    @Test
    fun explainPomskyPatternReturnsEnglish() {
        val explanation = PatternDescriber.explain("pomsky:^ 'hello' [digit]+")
        assertNotNull(explanation)
        assertTrue(explanation.isNotBlank())
    }

    @Test
    fun explainGlobReturnsNull() {
        assertNull(PatternDescriber.explain("glob:{feat,fix}:*"))
    }

    @Test
    fun explainNamedReturnsNull() {
        assertNull(PatternDescriber.explain("@conventional-commit"))
    }

    @Test
    fun explainBlankReturnsNull() {
        assertNull(PatternDescriber.explain(""))
    }

    // -- generateExamples() tests --

    @Test
    fun generateExamplesForBareRegex() {
        val examples = PatternDescriber.generateExamples("^[A-Z]{2}\\d{4}$")
        assertNotNull(examples)
        assertTrue(examples.matching.isNotEmpty(), "Should produce matching examples")
    }

    @Test
    fun generateExamplesMatchingStringsActuallyMatch() {
        val pattern = "^[a-z]+$"
        val examples = PatternDescriber.generateExamples(pattern)
        assertNotNull(examples)
        val compiled = Regex(pattern)
        for (sample in examples.matching) {
            assertTrue(
                compiled.matches(sample),
                "Matching example '$sample' should match pattern $pattern",
            )
        }
    }

    @Test
    fun generateExamplesForPomskyPattern() {
        val examples = PatternDescriber.generateExamples("pomsky:^ 'hello' [digit]+")
        assertNotNull(examples)
        assertTrue(examples.matching.isNotEmpty())
    }

    @Test
    fun generateExamplesGlobReturnsNull() {
        assertNull(PatternDescriber.generateExamples("glob:{feat,fix}:*"))
    }

    @Test
    fun generateExamplesNamedReturnsNull() {
        assertNull(PatternDescriber.generateExamples("@conventional"))
    }

    @Test
    fun generateExamplesBlankReturnsNull() {
        assertNull(PatternDescriber.generateExamples(""))
    }

    @Test
    fun generateExamplesRespectsMaxExamples() {
        val examples = PatternDescriber.generateExamples("^(a|b|c|d|e|f)$", maxExamples = 3)
        assertNotNull(examples)
        assertTrue(examples.matching.size <= 3, "Should respect maxExamples limit")
    }

    @Test
    fun generateExamplesWithSampleTextsUsesProvidedContent() {
        val examples = PatternDescriber.generateExamples(
            "^(feat|fix):.*",
            sampleTexts = listOf("add login page"),
        )
        assertNotNull(examples)
        assertTrue(examples.matching.isNotEmpty())
        val hasCustomText = examples.matching.any { it.contains("add login page") }
        assertTrue(hasCustomText, "Expected sampleTexts content in examples: ${examples.matching}")
    }

    @Test
    fun generateExamplesWithSampleTextsCoversBranches() {
        val examples = PatternDescriber.generateExamples(
            "^(feat|fix|chore):.*",
            sampleTexts = listOf("update deps"),
        )
        assertNotNull(examples)
        assertTrue(examples.matching.size > 1, "Should produce multiple branch samples")
    }

    @Test
    fun `optional and star patterns give no empty example`() {
        for (pattern in listOf(".*", "a?", "x*", "pomsky:'a'?")) {
            val examples = assertNotNull(PatternDescriber.generateExamples(pattern))
            assertTrue("" !in examples.matching, "empty example for $pattern: ${examples.matching}")
            assertTrue(examples.matching.isNotEmpty(), "no example left for $pattern")
        }
    }

    @Test
    fun `non-empty examples are kept as generated`() {
        assertEquals(listOf("hello", "test", "example"), assertNotNull(PatternDescriber.generateExamples(".*")).matching)
        assertEquals(listOf("x", "xxx"), assertNotNull(PatternDescriber.generateExamples("x*")).matching)
    }
}
