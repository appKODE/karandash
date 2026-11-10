package io.karandash.validators

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleFormatValidatorTest {
    @Test
    fun testValidTitleMatchingPatternProducesNoFindings() {
        val config = TitleFormatConfig(pattern = "^(feat|fix): .*")
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "feat: add new feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testTitleNotMatchingPatternProducesError() {
        val config = TitleFormatConfig(pattern = "^(feat|fix): .*")
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "invalid title format")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("titleFormat", findings[0].rule)
        assertTrue(findings[0].message.contains("does not match required pattern"))
    }

    @Test
    fun `title findings carry the TITLE_FORMAT_MISMATCH and TITLE_LENGTH_VIOLATION codes`() {
        val validator = TitleFormatValidator(TitleFormatConfig(pattern = "^(feat|fix): .*", maxLength = 10))

        val findings = validator.validate(createTestMergeRequest(title = "invalid title format"))

        assertEquals(listOf("E0200", "E0201"), findings.map { it.errorCode }, findings.toString())
    }

    @Test
    fun testInvalidRegexPatternReturnsError() {
        val config = TitleFormatConfig(pattern = "[invalid regex((")
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "feat: test")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("titleFormat", findings[0].rule)
        assertTrue(findings[0].message.contains("Invalid regex pattern"))
    }

    @Test
    fun testTitleTooShortProducesError() {
        val config = TitleFormatConfig(pattern = ".*", minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "short")

        val findings = validator.validate(mr)
        val lengthFindings = findings.filter { it.message.contains("too short") }
        assertEquals(1, lengthFindings.size)
        assertEquals(Severity.ERROR, lengthFindings[0].severity)
    }

    @Test
    fun testTitleExactlyAtMinLengthPasses() {
        val config = TitleFormatConfig(pattern = ".*", minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "1234567890")

        val findings = validator.validate(mr)
        val lengthFindings = findings.filter { it.message.contains("too short") }
        assertTrue(lengthFindings.isEmpty())
    }

    @Test
    fun testTitleTooLongProducesWarning() {
        val config = TitleFormatConfig(pattern = ".*", maxLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "12345678901")

        val findings = validator.validate(mr)
        val lengthFindings = findings.filter { it.message.contains("too long") }
        assertEquals(1, lengthFindings.size)
        assertEquals(Severity.WARNING, lengthFindings[0].severity)
        assertTrue(lengthFindings[0].message.contains("max: 10"))
        assertTrue(lengthFindings[0].message.contains("actual: 11"))
    }

    @Test
    fun testTitleExactlyAtMaxLengthPasses() {
        val config = TitleFormatConfig(pattern = ".*", maxLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "1234567890")

        val findings = validator.validate(mr)
        val lengthFindings = findings.filter { it.message.contains("too long") }
        assertTrue(lengthFindings.isEmpty())
    }

    @Test
    fun testEmptyTitleFailsPatternAndMinLength() {
        val config = TitleFormatConfig(pattern = ".+", minLength = 1)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "")

        val findings = validator.validate(mr)
        assertTrue(findings.size >= 2)
        assertTrue(findings.any { it.message.contains("does not match required pattern") })
        assertTrue(findings.any { it.message.contains("too short") })
    }

    @Test
    fun testUnicodeAndEmojiCharactersHandled() {
        val config = TitleFormatConfig(pattern = ".*")
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "feat: Add celebration feature")

        val findings = validator.validate(mr)
        val patternFindings = findings.filter { it.message.contains("does not match required pattern") }
        assertTrue(patternFindings.isEmpty())
    }

    @Test
    fun testRequireTicketWithTicketInTitlePasses() {
        val config = TitleFormatConfig(pattern = ".*", requireTicket = true)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "PROJ-123: add new feature")

        val findings = validator.validate(mr)
        val ticketFindings = findings.filter { it.message.contains("ticket reference") }
        assertTrue(ticketFindings.isEmpty())
    }

    @Test
    fun testRequireTicketWithoutTicketProducesError() {
        val config = TitleFormatConfig(pattern = ".*", requireTicket = true)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "add new feature without ticket")

        val findings = validator.validate(mr)
        val ticketFindings = findings.filter { it.message.contains("ticket reference") }
        assertEquals(1, ticketFindings.size)
        assertEquals(Severity.ERROR, ticketFindings[0].severity)
    }

    @Test
    fun testRequireTicketFalseDoesNotCheck() {
        val config = TitleFormatConfig(pattern = ".*", requireTicket = false)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "add new feature without ticket")

        val findings = validator.validate(mr)
        val ticketFindings = findings.filter { it.message.contains("ticket reference") }
        assertTrue(ticketFindings.isEmpty())
    }

    @Test
    fun testRequireTicketWithTicketInMiddleOfTitle() {
        val config = TitleFormatConfig(pattern = ".*", requireTicket = true)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "feat: implement PROJ-456 login feature")

        val findings = validator.validate(mr)
        val ticketFindings = findings.filter { it.message.contains("ticket reference") }
        assertTrue(ticketFindings.isEmpty())
    }
}
