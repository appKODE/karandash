package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for file metric predicates registered by [FileMetricPredicates].
 */
class FileMetricPredicatesTest {
    private fun makeMr(changes: List<FileChange> = emptyList(),): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "test",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/x",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = changes,
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun evaluator(mr: MergeRequest): ExpressionEvaluator<ConditionContext> {
        val ctx = ConditionContext(mr = mr)
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry)
        val ev = ExpressionEvaluator(ctx)
        ev.registerAll(registry)
        return ev
    }

    private val addedFile = FileChange(path = "new.ts", changeType = ChangeType.ADDED)
    private val deletedFile = FileChange(path = "old.kt", changeType = ChangeType.DELETED)
    private val modifiedFile = FileChange(path = "src/Main.kt", changeType = ChangeType.MODIFIED)

    // ── file_count ───────────────────────────────────────────────────────

    @Test
    fun `file_count greater than threshold returns true`() {
        val ev = evaluator(makeMr(changes = listOf(addedFile, modifiedFile, deletedFile)))
        assertTrue(ev.evaluate("file_count > 2"))
    }

    @Test
    fun `file_count greater than threshold returns false at boundary`() {
        val ev = evaluator(makeMr(changes = listOf(addedFile, modifiedFile)))
        assertFalse(ev.evaluate("file_count > 2"))
    }

    @Test
    fun `file_count equals threshold returns true`() {
        val ev = evaluator(makeMr(changes = listOf(addedFile, modifiedFile)))
        assertTrue(ev.evaluate("file_count == 2"))
    }

    // ── file_count_added ─────────────────────────────────────────────────

    @Test
    fun `file_count_added greater than threshold returns true`() {
        val added2 = FileChange(path = "another.ts", changeType = ChangeType.ADDED)
        val ev = evaluator(makeMr(changes = listOf(addedFile, added2, modifiedFile)))
        assertTrue(ev.evaluate("file_count_added > 1"))
    }

    @Test
    fun `file_count_added greater than threshold returns false when not enough`() {
        val ev = evaluator(makeMr(changes = listOf(addedFile, modifiedFile)))
        assertFalse(ev.evaluate("file_count_added > 1"))
    }

    // ── file_count_deleted ───────────────────────────────────────────────

    @Test
    fun `file_count_deleted greater than threshold returns true`() {
        val deleted2 = FileChange(path = "old2.kt", changeType = ChangeType.DELETED)
        val ev = evaluator(makeMr(changes = listOf(deletedFile, deleted2)))
        assertTrue(ev.evaluate("file_count_deleted > 1"))
    }

    @Test
    fun `file_count_deleted returns false when no deleted files`() {
        val ev = evaluator(makeMr(changes = listOf(addedFile, modifiedFile)))
        assertFalse(ev.evaluate("file_count_deleted > 0"))
    }

    // ── file_count_modified ──────────────────────────────────────────────

    @Test
    fun `file_count_modified greater than threshold returns true`() {
        val modified2 = FileChange(path = "src/Other.kt", changeType = ChangeType.MODIFIED)
        val ev = evaluator(makeMr(changes = listOf(modifiedFile, modified2)))
        assertTrue(ev.evaluate("file_count_modified > 1"))
    }

    @Test
    fun `file_count_modified returns false when no modified files`() {
        val ev = evaluator(makeMr(changes = listOf(addedFile, deletedFile)))
        assertFalse(ev.evaluate("file_count_modified > 0"))
    }

    // ── has_file_type ────────────────────────────────────────────────────

    @Test
    fun `has_file_type returns true when extension present`() {
        val ev = evaluator(makeMr(changes = listOf(addedFile)))
        assertTrue(ev.evaluate("""has_file_type(".ts")"""))
    }

    @Test
    fun `has_file_type returns false when extension absent`() {
        val ev = evaluator(makeMr(changes = listOf(modifiedFile)))
        assertFalse(ev.evaluate("""has_file_type(".ts")"""))
    }

    // ── file_added ───────────────────────────────────────────────────────

    @Test
    fun `file_added returns true when added file matches pattern`() {
        val sqlFile = FileChange(path = "db/migration.sql", changeType = ChangeType.ADDED)
        val ev = evaluator(makeMr(changes = listOf(sqlFile)))
        assertTrue(ev.evaluate("""file_added(".sql")"""))
    }

    @Test
    fun `file_added returns false when no added file matches`() {
        val ev = evaluator(makeMr(changes = listOf(modifiedFile)))
        assertFalse(ev.evaluate("""file_added(".sql")"""))
    }

    @Test
    fun `file_added returns false for modified file with matching name`() {
        val sqlModified = FileChange(path = "db/migration.sql", changeType = ChangeType.MODIFIED)
        val ev = evaluator(makeMr(changes = listOf(sqlModified)))
        assertFalse(ev.evaluate("""file_added(".sql")"""))
    }
}
