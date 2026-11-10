package io.karandash.validators

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pin: description-format `mrSections[].required` is live and covered here. */
class DescriptionFormatValidatorTest {
    @Test
    fun testEmptyConfigProducesNoFindings() {
        val config = DescriptionFormatConfig()
        val validator = DescriptionFormatValidator(config)
        val mr = createTestMergeRequest(description = "Any description")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testRequiredSectionPresentPasses() {
        val config = DescriptionFormatConfig(
            mrSections = listOf(
                SectionRule(name = "TASK", pattern = "^TASK: .+", required = true)
            )
        )
        val validator = DescriptionFormatValidator(config)
        val mr = createTestMergeRequest(description = "Some intro\n\nTASK: https://jira.example.com/MAPS-123")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testRequiredSectionMissingFails() {
        val config = DescriptionFormatConfig(
            mrSections = listOf(
                SectionRule(name = "TASK", pattern = "^TASK: .+", required = true)
            )
        )
        val validator = DescriptionFormatValidator(config)
        val mr = createTestMergeRequest(description = "Some description without task section")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("missing the required section TASK"))
    }

    @Test
    fun testOptionalSectionMissingPasses() {
        val config = DescriptionFormatConfig(
            mrSections = listOf(
                SectionRule(name = "Labels", pattern = "^Labels: .+", required = false)
            )
        )
        val validator = DescriptionFormatValidator(config)
        val mr = createTestMergeRequest(description = "Description without labels")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testMultipleRequiredSections() {
        val config = DescriptionFormatConfig(
            mrSections = listOf(
                SectionRule(name = "TASK", pattern = "^TASK: .+", required = true),
                SectionRule(name = "CHANGELOG", pattern = "^CHANGELOG: .+", required = true),
                SectionRule(name = "Closes", pattern = "^Closes MAPS-\\d+", required = true),
                SectionRule(name = "Proof", pattern = "^Proof: .+", required = true),
            )
        )
        val validator = DescriptionFormatValidator(config)
        val mr = createTestMergeRequest(
            description = """
                Optional intro text

                TASK: https://jira.example.com/MAPS-123

                CHANGELOG: [MAPS-123] Add new feature.

                Closes MAPS-123

                Proof: screenshots attached
            """.trimIndent()
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings but got: $findings")
    }

    @Test
    fun testEmptyDescriptionWithRequiredSectionsFails() {
        val config = DescriptionFormatConfig(
            mrSections = listOf(
                SectionRule(name = "TASK", pattern = "^TASK: .+", required = true)
            )
        )
        val validator = DescriptionFormatValidator(config)
        val mr = createTestMergeRequest(description = null)

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("empty"))
    }

    @Test
    fun testInvalidSectionPatternProducesError() {
        val config = DescriptionFormatConfig(
            mrSections = listOf(
                SectionRule(name = "BAD", pattern = "[invalid", required = true)
            )
        )
        val validator = DescriptionFormatValidator(config)
        val mr = createTestMergeRequest(description = "Some description")

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("Invalid regex pattern") })
    }
}
