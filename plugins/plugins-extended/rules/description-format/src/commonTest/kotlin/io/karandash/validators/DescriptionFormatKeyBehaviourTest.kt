package io.karandash.validators

import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescriptionFormatKeyBehaviourTest {
    private fun messages(
        description: String?,
        vararg sections: SectionRule,
    ): List<String> =
        DescriptionFormatValidator(DescriptionFormatConfig(sections.toList()))
            .validate(createTestMergeRequest(description = description))
            .map { it.message }

    @Test
    fun `mrSections checks every listed section`() {
        val result = messages("## Why", SectionRule("Why", "## Why", true), SectionRule("Testing", "## Testing", true))
        assertEquals(1, result.size)
        assertEquals(0, messages("## Why\n## Testing", SectionRule("Why", "## Why", true), SectionRule("Testing", "## Testing", true)).size)
    }

    @Test
    fun `mrSections name labels the missing section`() {
        assertTrue(messages("text", SectionRule("Rollout plan", "## Rollout", true)).single().contains("Rollout plan"))
    }

    @Test
    fun `mrSections pattern decides which line counts as the section`() {
        val section = SectionRule("Why", "^## Why$", true)
        assertEquals(0, messages("## Why", section).size)
        assertEquals(1, messages("## Because", section).size)
    }

    @Test
    fun `mrSections required flags a missing section only when true`() {
        assertEquals(1, messages("text", SectionRule("Why", "## Why", true)).size)
        assertEquals(0, messages("text", SectionRule("Why", "## Why", false)).size)
    }
}
