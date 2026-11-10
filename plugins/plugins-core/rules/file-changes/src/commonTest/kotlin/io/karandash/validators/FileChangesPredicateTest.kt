package io.karandash.validators

import io.karandash.api.context.ConditionContext
import io.karandash.conditions.ExpressionEvaluator
import io.karandash.conditions.MutablePredicateRegistry
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for predicates registered by [FileChangesRuleModule.registerPredicates]:
 * `file_path_matches` and `has_changes_in`.
 */
class FileChangesPredicateTest {
    private fun makeMr(changes: List<FileChange> = emptyList(),): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "feat: test",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/test",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = changes,
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun fileChange(path: String) = FileChange(path = path, changeType = ChangeType.MODIFIED)

    private fun evaluator(mr: MergeRequest): ExpressionEvaluator<ConditionContext> {
        val context = ConditionContext(mr = mr)
        val registry = MutablePredicateRegistry<ConditionContext>()
        FileChangesRuleModule().registerPredicates(registry)
        val ev = ExpressionEvaluator(context)
        ev.registerAll(registry)
        return ev
    }

    // ── file_path_matches ─────────────────────────────────────────────────

    @Test
    fun `file_path_matches returns true when a change path matches glob`() {
        val mr = makeMr(changes = listOf(fileChange("src/main/Foo.kt")))
        assertTrue(evaluator(mr).evaluate("file_path_matches('**/*.kt')"))
    }

    @Test
    fun `file_path_matches returns false when no change path matches`() {
        val mr = makeMr(changes = listOf(fileChange("src/main/Foo.java")))
        assertFalse(evaluator(mr).evaluate("file_path_matches('**/*.kt')"))
    }

    @Test
    fun `file_path_matches accepts double-quoted argument`() {
        val mr = makeMr(changes = listOf(fileChange("README.md")))
        assertTrue(evaluator(mr).evaluate("file_path_matches(\"*.md\")"))
    }

    @Test
    fun `file_path_matches returns false on empty changes`() {
        assertFalse(evaluator(makeMr()).evaluate("file_path_matches('**/*.kt')"))
    }

    @Test
    fun `file_path_matches with multiple changes stops at first match`() {
        val mr = makeMr(changes = listOf(fileChange("docs/readme.md"), fileChange("src/Foo.kt")))
        assertTrue(evaluator(mr).evaluate("file_path_matches('**/*.kt')"))
    }

    // ── has_changes_in ────────────────────────────────────────────────────

    @Test
    fun `has_changes_in returns true when path prefix matches`() {
        val mr = makeMr(changes = listOf(fileChange("src/main/Foo.kt")))
        assertTrue(evaluator(mr).evaluate("has_changes_in('src/main')"))
    }

    @Test
    fun `has_changes_in returns false when no path has the prefix`() {
        val mr = makeMr(changes = listOf(fileChange("test/FooTest.kt")))
        assertFalse(evaluator(mr).evaluate("has_changes_in('src/main')"))
    }
}
