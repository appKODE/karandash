package io.karandash.validators

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchNamingValidatorTest {
    @Test
    fun testSourceBranchMatchesSinglePattern() {
        val config = BranchNamingConfig(
            pattern = "feature/.*",
            requireSourceMatch = true,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        val mr = createTestMergeRequest(
            sourceBranch = "feature/add-validation",
            targetBranch = "main"
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings for valid branch name")
    }

    @Test
    fun testSourceBranchDoesNotMatchSinglePattern() {
        val config = BranchNamingConfig(
            pattern = "feature/.*",
            requireSourceMatch = true,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        val mr = createTestMergeRequest(
            sourceBranch = "bugfix/fix-issue",
            targetBranch = "main"
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("branchNaming", findings[0].rule)
        assertTrue(findings[0].message.contains("Source branch"))
    }

    @Test
    fun testSourceBranchMatchesMultiplePatterns() {
        val config = BranchNamingConfig(
            allowedPatterns = listOf("feature/.*", "bugfix/.*", "hotfix/.*"),
            requireSourceMatch = true,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        val mr1 = createTestMergeRequest(sourceBranch = "feature/new-feature", targetBranch = "main")
        val mr2 = createTestMergeRequest(sourceBranch = "bugfix/fix-bug", targetBranch = "main")
        val mr3 = createTestMergeRequest(sourceBranch = "hotfix/urgent-fix", targetBranch = "main")

        assertTrue(validator.validate(mr1).isEmpty())
        assertTrue(validator.validate(mr2).isEmpty())
        assertTrue(validator.validate(mr3).isEmpty())
    }

    @Test
    fun testSourceBranchDoesNotMatchAnyPattern() {
        val config = BranchNamingConfig(
            allowedPatterns = listOf("feature/.*", "bugfix/.*"),
            requireSourceMatch = true,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        val mr = createTestMergeRequest(
            sourceBranch = "random-branch",
            targetBranch = "main"
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
    }

    @Test
    fun testTargetBranchValidation() {
        val config = BranchNamingConfig(
            pattern = "main|develop|staging",
            requireSourceMatch = false,
            requireTargetMatch = true
        )
        val validator = BranchNamingValidator(config)

        val validMr = createTestMergeRequest(sourceBranch = "feature/test", targetBranch = "main")
        val invalidMr = createTestMergeRequest(sourceBranch = "feature/test", targetBranch = "production")

        assertTrue(validator.validate(validMr).isEmpty())
        assertEquals(1, validator.validate(invalidMr).size)
    }

    @Test
    fun testBothSourceAndTargetValidation() {
        val config = BranchNamingConfig(
            allowedPatterns = listOf("feature/.*", "main", "develop"),
            requireSourceMatch = true,
            requireTargetMatch = true
        )
        val validator = BranchNamingValidator(config)

        val validMr = createTestMergeRequest(
            sourceBranch = "feature/new-feature",
            targetBranch = "main"
        )
        val invalidSourceMr = createTestMergeRequest(
            sourceBranch = "invalid-branch",
            targetBranch = "main"
        )
        val invalidTargetMr = createTestMergeRequest(
            sourceBranch = "feature/test",
            targetBranch = "production"
        )

        assertTrue(validator.validate(validMr).isEmpty())
        assertEquals(1, validator.validate(invalidSourceMr).size)
        assertEquals(1, validator.validate(invalidTargetMr).size)
    }

    @Test
    fun testNoValidationWhenNotRequired() {
        val config = BranchNamingConfig(
            pattern = "feature/.*",
            requireSourceMatch = false,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        val mr = createTestMergeRequest(
            sourceBranch = "any-branch",
            targetBranch = "any-target"
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testNoPatternsConfigured() {
        val config = BranchNamingConfig(
            pattern = null,
            allowedPatterns = emptyList(),
            requireSourceMatch = true,
            requireTargetMatch = true
        )
        val validator = BranchNamingValidator(config)

        val mr = createTestMergeRequest(
            sourceBranch = "any-branch",
            targetBranch = "any-target"
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "No validation should occur when no patterns configured")
    }

    @Test
    fun testComplexRegexPatterns() {
        val config = BranchNamingConfig(
            pattern = "[A-Z]+-[0-9]+-.+",
            requireSourceMatch = true,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        val validMr = createTestMergeRequest(sourceBranch = "PROJ-123-fix-bug", targetBranch = "main")
        val invalidMr = createTestMergeRequest(sourceBranch = "proj-123-fix-bug", targetBranch = "main")

        assertTrue(validator.validate(validMr).isEmpty())
        assertEquals(1, validator.validate(invalidMr).size)
    }

    @Test
    fun testInvalidRegexPatternReturnsError() {
        val config = BranchNamingConfig(
            pattern = "[invalid regex((",
            requireSourceMatch = true,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        val mr = createTestMergeRequest(
            sourceBranch = "feature/test",
            targetBranch = "main"
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("branchNaming", findings[0].rule)
        assertTrue(findings[0].message.contains("Invalid regex pattern"))
    }

    @Test
    fun testInvalidRegexInAllowedPatternsReturnsError() {
        val config = BranchNamingConfig(
            allowedPatterns = listOf("feature/.*", "[invalid)", "bugfix/.*"),
            requireSourceMatch = true,
            requireTargetMatch = false
        )
        val validator = BranchNamingValidator(config)

        val mr = createTestMergeRequest(
            sourceBranch = "feature/test",
            targetBranch = "main"
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("Invalid regex pattern"))
    }
}
