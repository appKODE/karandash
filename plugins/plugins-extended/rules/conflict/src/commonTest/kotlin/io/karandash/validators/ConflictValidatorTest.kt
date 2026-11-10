package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Severity
import io.karandash.sdk.config.BlockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictValidatorTest {
    private fun createMockMR(
        state: MergeRequestState = MergeRequestState.OPENED,
        hasConflicts: Boolean? = null,
        mergeStatus: String? = null
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = "Test description",
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User(username = "testuser", name = "Test User"),
            state = state,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
            hasConflicts = hasConflicts,
            mergeStatus = mergeStatus
        )

    @Test
    fun testConflictDetectionBlocksMerge() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(mergeStatus = "cannot_be_merged")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Should detect conflicts")
        assertEquals(Severity.ERROR, findings.first().severity, "Should be ERROR severity")
        assertEquals("conflict", findings.first().rule, "Should have correct rule name")
        assertTrue(findings.first().message.contains("conflicts"), "Should mention conflicts")
    }

    @Test
    fun testConflictStateDetectsConflicts() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(mergeStatus = "conflicts")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Should detect conflicts")
        assertEquals(Severity.ERROR, findings.first().severity, "Should be ERROR severity")
    }

    @Test
    fun testHasConflictsFlagDetectsConflicts() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(hasConflicts = true)
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Should detect conflicts from hasConflicts flag")
        assertEquals(Severity.ERROR, findings.first().severity)
    }

    @Test
    fun testMergeStatusDirtyDetectsConflicts() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(mergeStatus = "dirty")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Should detect conflicts from merge status")
        assertEquals(Severity.ERROR, findings.first().severity)
    }

    @Test
    fun testNoConflictsProducesNoFindings() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(state = MergeRequestState.OPENED)
        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should not produce findings for clean MR")
    }

    @Test
    fun testDisabledValidationProducesNoFindings() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.OFF)
        val validator = ConflictValidator(config)
        val mr = createMockMR(mergeStatus = "cannot_be_merged")
        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should not validate when disabled")
    }

    @Test
    fun testNoConflictsDetectedStatusDoesNotFalsePositive() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(mergeStatus = "no_conflicts_detected")
        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "no_conflicts_detected should NOT trigger conflict finding")
    }

    @Test
    fun testConflictsResolvedStatusDoesNotFalsePositive() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(mergeStatus = "conflicts_resolved")
        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "conflicts_resolved should NOT trigger conflict finding")
    }

    @Test
    fun testCleanStatusProducesNoFindings() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(mergeStatus = "clean")
        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "clean status should not trigger conflict")
    }

    @Test
    fun testConflictingStatusDetectsConflict() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(mergeStatus = "conflicting")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "conflicting should be detected")
    }

    @Test
    fun testHasConflictsTrueWithNonConflictStatusDetects() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(hasConflicts = true, mergeStatus = "clean")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "hasConflicts=true should detect conflict regardless of status")
    }

    @Test
    fun testHasConflictsFalseWithConflictStatusDetects() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(hasConflicts = false, mergeStatus = "conflict")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "conflict status should still detect even if hasConflicts=false")
    }

    @Test
    fun testUnmergeableStatusDetects() {
        val config = ConflictConfig(blockOnConflicts = BlockMode.FAIL)
        val validator = ConflictValidator(config)
        val mr = createMockMR(mergeStatus = "unmergeable")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "unmergeable should be detected")
    }
}
