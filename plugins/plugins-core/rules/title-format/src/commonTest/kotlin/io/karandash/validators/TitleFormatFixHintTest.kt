package io.karandash.validators

import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.mismatch
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TitleFormatFixHintTest {
    @Test
    fun `pattern mismatch states the problem and puts the fix in the suggestion`() {
        val finding = TitleFormatValidator(TitleFormatConfig(pattern = "^[A-Z]+-\\d+: .+$"))
            .validate(createTestMergeRequest(title = "add login"))
            .single()

        assertEquals("MR title does not match required pattern.", finding.message)
        assertTrue(finding.suggestion.orEmpty().startsWith("Change the title to match the format, e.g. `"), finding.suggestion)
        assertNotNull(finding.metadata?.get(WellKnownMetadataKeys.EXPECTED_FORMAT))
        assertEquals(0, finding.mismatch()?.position)
    }

    @Test
    fun `a pomsky title pattern puts its test examples in the finding metadata`() {
        val pattern = "pomsky:test { match 'fix: crash'; } ('feat' | 'fix') ': ' .+"
        val finding = TitleFormatValidator(
            TitleFormatConfig(pattern = pattern),
            patterns = PatternAwareCompiler {
                _,
                _,
                _
                ->
                Regex("^(feat|fix): .+$")
            }
        ).validate(createTestMergeRequest(title = "chore stuff"))
            .single()

        assertEquals("fix: crash", finding.metadata?.get(WellKnownMetadataKeys.PATTERN_EXAMPLES))
    }

    @Test
    fun `a deterministic correction becomes a SetTitle fix`() {
        val finding = TitleFormatValidator(TitleFormatConfig(pattern = "^(feat|fix): .+$"))
            .validate(createTestMergeRequest(title = "feat add x"))
            .single()

        assertEquals("Change the title to `feat: add x`.", finding.suggestion)
        assertEquals(listOf<FixSuggestion>(FixSuggestion.SetTitle("feat: add x")), finding.fixSuggestions)
    }

    @Test
    fun `a synthetic example such as JIRA-0 is not applied automatically`() {
        val finding = TitleFormatValidator(TitleFormatConfig(pattern = "^JIRA-\\d+: .+$"))
            .validate(createTestMergeRequest(title = "add login"))
            .single()

        assertTrue(finding.suggestion.orEmpty().contains("e.g."), finding.suggestion)
        assertFalse(finding.fixSuggestions.any { it is FixSuggestion.SetTitle })
    }

    @Test
    fun `a title missing one of several alternatives gets no SetTitle fix`() {
        val finding = TitleFormatValidator(TitleFormatConfig(pattern = "^(feat|fix)!?: .+$"))
            .validate(createTestMergeRequest(title = "feat add"))
            .single()

        assertTrue(finding.suggestion.orEmpty().contains("e.g."), finding.suggestion)
        assertTrue(finding.fixSuggestions.isEmpty(), finding.fixSuggestions.toString())
    }
}
