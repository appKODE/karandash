package io.karandash.contracts.fw

import io.karandash.contracts.support.parseYamlConfig
import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.ReviewState
import io.karandash.models.User
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.validators.CodeownersConfig
import io.karandash.validators.CodeownersValidator
import io.karandash.validators.CommitMessagesConfig
import io.karandash.validators.CommitMessagesValidator
import io.karandash.validators.LabelsConfig
import io.karandash.validators.LabelsValidator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Documented rule semantics that the rule implementations do not honour.
 *
 * - codeowners counts any review as approval (no `ReviewState` filter).
 * - commit-messages rejects the documented `format: any`.
 * - `rules: { x: true }` shorthand is dropped.
 * - `requiredLabels` has no wildcard support.
 */
class RuleSemanticsContractTest {
    @Test
    fun `codeowners does not count a changes-requested review as owner approval`() {
        val mr = createTestMergeRequest(
            changes = listOf(FileChange(path = "src/Main.kt", changeType = ChangeType.MODIFIED)),
            approvals = listOf(
                Approval(user = User(username = "alice", name = "Alice"), state = ReviewState.CHANGES_REQUESTED),
            ),
        )
        val validator = CodeownersValidator(CodeownersConfig(path = "CODEOWNERS")) { "* @alice" }

        val findings = validator.validate(mr)

        assertTrue(
            findings.isNotEmpty(),
            "codeowners treated a CHANGES_REQUESTED review from @alice as an owner approval",
        )
    }

    @Test
    fun `commit-messages format any accepts free-form messages`() {
        val mr = createTestMergeRequest(
            commits = listOf(
                Commit(
                    sha = "abcdef1234567",
                    message = "random message",
                    author = CommitAuthor(name = "Dev", email = "dev@example.com"),
                    timestamp = "2026-01-01T00:00:00Z",
                ),
            ),
        )

        val findings = CommitMessagesValidator(CommitMessagesConfig(format = "any")).validate(mr)

        assertTrue(
            findings.isEmpty(),
            "documented format 'any' produced findings: ${findings.map { it.message }}",
        )
    }

    @Test
    fun `boolean rule shorthand enables the rule`() =
        runTest {
            val config = parseYamlConfig(
                """
                rules:
                  commit-messages: true
                """,
            )

            assertTrue(
                "commitMessages" in config.rules.ruleConfigs,
                "'commit-messages: true' was dropped; ruleConfigs=${config.rules.ruleConfigs.keys}",
            )
        }

    @Test
    fun `requiredLabels supports scoped wildcard`() {
        val mr = createTestMergeRequest(labels = listOf("type::bug"))

        val findings = LabelsValidator(LabelsConfig(requiredLabels = listOf("type::*"))).validate(mr)

        assertTrue(
            findings.isEmpty(),
            "requiredLabels 'type::*' did not match label 'type::bug': ${findings.map { it.message }}",
        )
    }
}
