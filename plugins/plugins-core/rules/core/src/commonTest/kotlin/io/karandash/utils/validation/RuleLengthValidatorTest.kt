package io.karandash.utils.validation

import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

/** rules:core length check reuses the core/common LengthValidator. */
class RuleLengthValidatorTest {
    private fun check(
        text: String,
        min: Int?,
        max: Int?,
    ) = RuleLengthValidator.checkLength(text, min, max, "rule", "Title", "fix", null, Severity.WARNING)

    @Test
    fun `too long finding uses the configured severity`() {
        val findings = check("a very long title", min = null, max = 5)
        assertEquals(listOf(Severity.WARNING), findings.map { it.severity })
        assertEquals(
            LengthValidator.checkLength("a very long title", null, 5, "rule", "Title", "fix").map {
                it.message
            },
            findings.map { it.message }
        )
    }

    @Test
    fun `too short finding stays an error`() {
        assertEquals(listOf(Severity.ERROR), check("hi", min = 5, max = 10).map { it.severity })
    }

    @Test
    fun `no findings within bounds`() {
        assertEquals(emptyList(), check("hello", min = 1, max = 10))
    }
}
