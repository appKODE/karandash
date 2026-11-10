package io.karandash.validators

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabelsValidatorTest {
    @Test
    fun `should pass when all required labels are present`() {
        val config = LabelsConfig(
            requiredLabels = listOf("bug", "priority-high")
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("bug", "priority-high", "reviewed"))

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should have no findings when all required labels are present")
    }

    @Test
    fun `should fail when required label is missing`() {
        val config = LabelsConfig(
            requiredLabels = listOf("bug", "priority-high")
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("bug"))

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should have one finding for missing label")
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("priority-high"), "Should mention missing label")
        assertEquals("labels", findings[0].rule)
    }

    @Test
    fun `label findings carry the MISSING_REQUIRED_LABEL and FORBIDDEN_LABEL_PRESENT codes`() {
        val validator = LabelsValidator(LabelsConfig(requiredLabels = listOf("bug"), forbiddenLabels = listOf("wip")))

        val findings = validator.validate(createTestMergeRequest(labels = listOf("wip")))

        assertEquals(listOf("E0202", "E0203"), findings.map { it.errorCode }, findings.toString())
    }

    @Test
    fun `should fail when forbidden label is present`() {
        val config = LabelsConfig(
            forbiddenLabels = listOf("wip", "do-not-merge")
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("bug", "wip"))

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should have one finding for forbidden label")
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("wip"), "Should mention forbidden label")
        assertEquals("labels", findings[0].rule)
    }

    @Test
    fun `wildcard required and forbidden labels match by glob`() {
        val required = LabelsValidator(LabelsConfig(requiredLabels = listOf("type::*")))
        assertTrue(required.validate(createTestMergeRequest(labels = listOf("type::bug"))).isEmpty())
        assertEquals(1, required.validate(createTestMergeRequest(labels = listOf("bug"))).size)

        val forbidden = LabelsValidator(LabelsConfig(forbiddenLabels = listOf("wip*")))
        val findings = forbidden.validate(createTestMergeRequest(labels = listOf("wip-ui", "bug")))
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("wip-ui"))
    }

    @Test
    fun `should pass when no forbidden labels are present`() {
        val config = LabelsConfig(
            forbiddenLabels = listOf("wip", "do-not-merge")
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("bug", "ready"))

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should have no findings when no forbidden labels are present")
    }

    @Test
    fun `should validate conditional labels with hasLabel condition`() {
        val config = LabelsConfig(
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(hasLabel = "breaking-change"),
                    requiredLabels = listOf("major-version")
                )
            )
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("breaking-change"))

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should have finding when conditional requirement not met")
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("major-version"), "Should mention required label")
        assertTrue(findings[0].message.contains("breaking-change"), "Should mention condition")
    }

    @Test
    fun `should pass conditional validation when condition is met`() {
        val config = LabelsConfig(
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(hasLabel = "breaking-change"),
                    requiredLabels = listOf("major-version")
                )
            )
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("breaking-change", "major-version"))

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should pass when conditional requirement is met")
    }

    @Test
    fun `should skip conditional validation when condition does not apply`() {
        val config = LabelsConfig(
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(hasLabel = "breaking-change"),
                    requiredLabels = listOf("major-version")
                )
            )
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("bug", "minor-fix"))

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should skip validation when condition does not apply")
    }

    @Test
    fun `should validate conditional labels with hasAnyLabel condition`() {
        val config = LabelsConfig(
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(hasAnyLabel = listOf("security", "critical")),
                    requiredLabels = listOf("reviewed-by-security")
                )
            )
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("security"))

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should have finding when conditional requirement not met")
        assertTrue(findings[0].message.contains("reviewed-by-security"))
    }

    @Test
    fun `should validate conditional labels with hasAllLabels condition`() {
        val config = LabelsConfig(
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(hasAllLabels = listOf("backend", "database")),
                    requiredLabels = listOf("dba-review")
                )
            )
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("backend", "database"))

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should have finding when conditional requirement not met")
        assertTrue(findings[0].message.contains("dba-review"))
    }

    @Test
    fun `should enforce conditional forbidden labels`() {
        val config = LabelsConfig(
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(hasLabel = "production"),
                    forbiddenLabels = listOf("wip")
                )
            )
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("production", "wip"))

        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should have finding for forbidden label in condition")
        assertTrue(findings[0].message.contains("wip"))
        assertTrue(findings[0].message.contains("forbidden"))
    }

    @Test
    fun `should handle multiple validation failures`() {
        val config = LabelsConfig(
            requiredLabels = listOf("type", "priority"),
            forbiddenLabels = listOf("wip"),
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(hasLabel = "hotfix"),
                    requiredLabels = listOf("approved")
                )
            )
        )
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("hotfix", "wip"))

        val findings = validator.validate(mr)

        assertTrue(findings.size >= 3, "Should have multiple findings")
        assertTrue(findings.any { it.message.contains("type") })
        assertTrue(findings.any { it.message.contains("priority") })
        assertTrue(findings.any { it.message.contains("wip") })
        assertTrue(findings.any { it.message.contains("approved") })
    }

    @Test
    fun `should pass with empty config`() {
        val config = LabelsConfig()
        val validator = LabelsValidator(config)
        val mr = createTestMergeRequest(labels = listOf("anything"))

        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should pass with empty config")
    }
}
