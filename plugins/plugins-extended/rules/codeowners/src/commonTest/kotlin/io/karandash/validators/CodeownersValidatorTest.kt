package io.karandash.validators

import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.ReviewState
import io.karandash.models.User
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeownersValidatorTest {
    @Test
    fun testNoCodeownersRequirement() {
        val config = CodeownersConfig(
            path = "CODEOWNERS",
            requireOwnerApproval = false
        )
        val validator = CodeownersValidator(config) { null }

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "src/main.kt", changeType = ChangeType.MODIFIED)
            ),
            approvals = emptyList()
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings when owner approval is not required")
    }

    @Test
    fun testCodeownersRequiredButNoChanges() {
        val config = CodeownersConfig(
            path = "CODEOWNERS",
            requireOwnerApproval = true
        )
        val validator = CodeownersValidator(config) { "* @owner1" }

        val mr = createTestMergeRequest(
            changes = emptyList(),
            approvals = emptyList()
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings when no files are changed")
    }

    @Test
    fun testCodeownersValidationWithApprovals() {
        val config = CodeownersConfig(
            path = "CODEOWNERS",
            requireOwnerApproval = true
        )
        val validator = CodeownersValidator(config) {
            """
            src/** @owner1
            """.trimIndent()
        }

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "src/main.kt", changeType = ChangeType.MODIFIED)
            ),
            approvals = listOf(
                Approval(
                    user = User(username = "owner1", name = "Owner One"),
                    approvedAt = "2024-01-01T12:00:00Z"
                )
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Expected no findings with matching codeowner approval")
    }

    @Test
    fun testMissingCodeownerApproval() {
        val config = CodeownersConfig(
            path = "CODEOWNERS",
            requireOwnerApproval = true
        )
        val validator = CodeownersValidator(config) {
            """
            database/** @db-team
            """.trimIndent()
        }

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "database/schema.sql", changeType = ChangeType.MODIFIED)
            ),
            approvals = emptyList()
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("@db-team"))
    }

    @Test
    fun testMultipleFileChangesRequiringOwners() {
        val config = CodeownersConfig(
            path = "CODEOWNERS",
            requireOwnerApproval = true
        )
        val validator = CodeownersValidator(config) {
            """
            * @core-team
            src/security/** @security-team
            database/** @db-team
            """.trimIndent()
        }

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "src/security/Auth.kt", changeType = ChangeType.MODIFIED),
                FileChange(path = "database/schema.sql", changeType = ChangeType.MODIFIED),
                FileChange(path = "docs/README.md", changeType = ChangeType.MODIFIED)
            ),
            approvals = emptyList()
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("@core-team"))
        assertTrue(findings[0].message.contains("@security-team"))
        assertTrue(findings[0].message.contains("@db-team"))
    }

    @Test
    fun testApprovalWithAtPrefixHandling() {
        val config = CodeownersConfig(
            path = "CODEOWNERS",
            requireOwnerApproval = true
        )
        val validator = CodeownersValidator(config) {
            """
            src/** @owner1
            """.trimIndent()
        }

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "src/main.kt", changeType = ChangeType.MODIFIED)
            ),
            approvals = listOf(
                Approval(
                    user = User(username = "owner1", name = "Owner One"),
                    approvedAt = "2024-01-01T12:00:00Z"
                )
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testConfigWithCustomCodeownersPath() {
        var requestedPath: String? = null
        val config = CodeownersConfig(
            path = ".github/CODEOWNERS",
            requireOwnerApproval = true
        )
        val validator = CodeownersValidator(config) { path ->
            requestedPath = path
            """
            src/** @owner1
            """.trimIndent()
        }

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "src/main.kt", changeType = ChangeType.MODIFIED)
            ),
            approvals = emptyList()
        )

        val findings = validator.validate(mr)
        assertEquals(".github/CODEOWNERS", requestedPath)
        assertEquals(1, findings.size)
    }

    @Test
    fun testLastMatchingRuleWins() {
        val config = CodeownersConfig(
            path = "CODEOWNERS",
            requireOwnerApproval = true
        )
        val validator = CodeownersValidator(config) {
            """
            src/** @owner1
            src/special/** @owner2
            """.trimIndent()
        }

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange(path = "src/special/main.kt", changeType = ChangeType.MODIFIED)
            ),
            approvals = listOf(
                Approval(
                    user = User(username = "owner1", name = "Owner One"),
                    approvedAt = "2024-01-01T12:00:00Z"
                )
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("@owner2"))
    }

    @Test
    fun changesRequestedReviewIsNotAnOwnerApproval() {
        val validator = CodeownersValidator(CodeownersConfig()) { "* @owner1" }
        val mr = createTestMergeRequest(
            changes = listOf(FileChange(path = "src/main.kt", changeType = ChangeType.MODIFIED)),
            approvals = listOf(
                Approval(user = User(username = "owner1", name = "Owner One"), state = ReviewState.CHANGES_REQUESTED),
            ),
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("@owner1"))
    }

    @Test
    fun defaultPathFindsGithubCodeowners() {
        val validator = CodeownersValidator(CodeownersConfig()) { path ->
            if (path == ".github/CODEOWNERS") "* @owner1" else null
        }
        val mr = createTestMergeRequest(
            changes = listOf(FileChange(path = "src/main.kt", changeType = ChangeType.MODIFIED)),
            approvals = emptyList(),
        )

        assertEquals(1, validator.validate(mr).size)
    }
}
