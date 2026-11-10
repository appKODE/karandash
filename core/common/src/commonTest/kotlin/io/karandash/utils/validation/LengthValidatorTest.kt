package io.karandash.utils.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LengthValidatorTest {
    private val ruleId = "test-rule"

    @Test
    fun `no findings when length is within bounds`() {
        val findings = LengthValidator.checkLength(
            text = "hello world",
            minLength = 5,
            maxLength = 20,
            ruleId = ruleId,
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `finding when text is too short`() {
        val findings = LengthValidator.checkLength(
            text = "hi",
            minLength = 5,
            maxLength = null,
            ruleId = ruleId,
            fieldName = "Title",
        )
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("too short"))
        assertTrue(findings[0].message.contains("min: 5"))
    }

    @Test
    fun `finding when text is too long`() {
        val findings = LengthValidator.checkLength(
            text = "a very long text that exceeds the limit",
            minLength = null,
            maxLength = 10,
            ruleId = ruleId,
            fieldName = "Subject",
        )
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("too long"))
        assertTrue(findings[0].message.contains("max: 10"))
    }

    @Test
    fun `both findings when text violates both bounds`() {
        // Text length 2 violates min=5, but also violates max=1
        // (practically impossible but tests both paths)
        val findingsShort = LengthValidator.checkLength(
            text = "hi",
            minLength = 5,
            maxLength = 1,
            ruleId = ruleId,
        )
        assertEquals(2, findingsShort.size)
    }

    @Test
    fun `no constraints means no findings`() {
        val findings = LengthValidator.checkLength(
            text = "anything",
            minLength = null,
            maxLength = null,
            ruleId = ruleId,
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `exact boundary values pass`() {
        val findings = LengthValidator.checkLength(
            text = "12345",
            minLength = 5,
            maxLength = 5,
            ruleId = ruleId,
        )
        assertTrue(findings.isEmpty())
    }
}
