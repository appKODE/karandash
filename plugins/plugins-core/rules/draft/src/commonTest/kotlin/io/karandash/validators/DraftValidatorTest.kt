package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Severity
import io.karandash.sdk.config.BlockMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftValidatorTest {
    private fun createMockMR(
        title: String = "Test MR",
        draft: Boolean = false
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = title,
            description = "Test description",
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User(username = "testuser", name = "Test User"),
            state = MergeRequestState.OPENED,
            draft = draft,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList()
        )

    @Test
    fun testDraftFlagBlocksMerge() {
        val config = DraftConfig(blockDraft = BlockMode.FAIL)
        val validator = DraftValidator(config)
        val mr = createMockMR(draft = true)
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Should detect draft MR")
        assertEquals(Severity.ERROR, findings.first().severity, "Should be ERROR severity")
        assertEquals("draftPrevention", findings.first().rule, "Should have correct rule name")
        assertTrue(findings.first().message.contains("draft"), "Should mention draft")
    }

    @Test
    fun testWIPInTitleBlocksMerge() {
        val config = DraftConfig(blockDraft = BlockMode.FAIL)
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "WIP: Implement new feature")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Should detect WIP in title")
        assertEquals(Severity.ERROR, findings.first().severity, "Should be ERROR severity")
        assertTrue(findings.first().message.contains("WIP"), "Should mention WIP")
    }

    @Test
    fun testWIPBracketPatternBlocksMerge() {
        val config = DraftConfig(blockDraft = BlockMode.FAIL)
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "[WIP] Implement new feature")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Should detect [WIP] pattern")
        assertEquals(Severity.ERROR, findings.first().severity, "Should be ERROR severity")
    }

    @Test
    fun testWIPColonPatternBlocksMerge() {
        val config = DraftConfig(blockDraft = BlockMode.FAIL)
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "wip: implement new feature")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Should detect wip: pattern")
        assertEquals(Severity.ERROR, findings.first().severity, "Should be ERROR severity")
    }

    @Test
    fun testCustomWIPPatterns() {
        val config = DraftConfig(
            blockDraft = BlockMode.FAIL,
            wipPatterns = listOf("DRAFT:", "\\[DRAFT\\]", "🚧")
        )
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "DRAFT: New feature")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Should detect custom WIP pattern")
        assertEquals(Severity.ERROR, findings.first().severity, "Should be ERROR severity")
    }

    @Test
    fun testNoDraftProducesNoFindings() {
        val config = DraftConfig(blockDraft = BlockMode.FAIL)
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "Implement new feature", draft = false)
        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should not produce findings for non-draft MR")
    }

    @Test
    fun testDisabledValidationProducesNoFindings() {
        val config = DraftConfig(blockDraft = BlockMode.OFF)
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "WIP: Test", draft = true)
        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "Should not validate when disabled")
    }

    @Test
    fun testDraftFlagAndWipTitleProducesExactlyOneFinding() {
        val config = DraftConfig(blockDraft = BlockMode.FAIL)
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "WIP: Implement new feature", draft = true)
        val findings = validator.validate(mr)

        assertEquals(1, findings.size, "Should produce exactly 1 finding, not 2 (dedup)")
        assertTrue(findings[0].message.contains("draft"), "Should report the draft flag, not WIP")
    }

    @Test
    fun testWipSubstringDoesNotFalsePositive() {
        val config = DraftConfig(blockDraft = BlockMode.FAIL)
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "WIPE the slate clean")
        val findings = validator.validate(mr)

        assertTrue(findings.isEmpty(), "WIPE should not match default WIP pattern with word boundary")
    }

    @Test
    fun testWipColonStillMatches() {
        val config = DraftConfig(blockDraft = BlockMode.FAIL)
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "WIP: my feature")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "WIP: should still match")
    }

    @Test
    fun testCustomPatternWithoutWordBoundaryStillWorks() {
        val config = DraftConfig(
            blockDraft = BlockMode.FAIL,
            wipPatterns = listOf("WIP")
        )
        val validator = DraftValidator(config)
        val mr = createMockMR(title = "WIPE the slate clean")
        val findings = validator.validate(mr)

        assertTrue(findings.isNotEmpty(), "Custom pattern without word boundary should match WIPE")
    }
}
