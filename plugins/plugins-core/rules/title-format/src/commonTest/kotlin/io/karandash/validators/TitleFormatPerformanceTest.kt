package io.karandash.validators

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleFormatPerformanceTest {
    @Test
    fun testTitleFormatValidatorReturnsInvalidRegexErrorOnFirstCall() {
        val config = TitleFormatConfig(pattern = "[invalid regex((")
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "feat: first call")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size, "Expected exactly one finding on first call")
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(
            findings[0].message.contains("Invalid regex pattern"),
            "Expected 'Invalid regex pattern' in message, got: ${findings[0].message}"
        )
    }

    @Test
    fun testTitleFormatValidatorReturnsIdenticalErrorOnSubsequentCalls() {
        val config = TitleFormatConfig(pattern = "[bad(")
        val validator = TitleFormatValidator(config)

        val firstFindings = validator.validate(createTestMergeRequest(title = "call one"))
        assertEquals(1, firstFindings.size)

        for (i in 2..5) {
            val findings = validator.validate(createTestMergeRequest(title = "call $i"))
            assertEquals(
                1,
                findings.size,
                "Expected exactly one finding on call $i"
            )
            assertEquals(
                firstFindings[0].rule,
                findings[0].rule,
                "Rule must be the same across calls"
            )
            assertEquals(
                firstFindings[0].message,
                findings[0].message,
                "Message must be identical across calls — proves cached error, not re-compiled"
            )
        }
    }
}
