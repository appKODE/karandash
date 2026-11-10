package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for config and time-based predicates registered by [ConfigPredicates].
 */
class ConfigPredicatesTest {
    private fun makeMr(commits: List<Commit> = emptyList(),): MergeRequest =
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
            commits = commits,
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun evaluator(ctx: ConditionContext): ExpressionEvaluator<ConditionContext> {
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry)
        val ev = ExpressionEvaluator(ctx)
        ev.registerAll(registry)
        return ev
    }

    private fun commit(timestamp: String): Commit =
        Commit(
            sha = "abc123",
            message = "fix",
            author = CommitAuthor(name = "Alice", email = "alice@example.com"),
            timestamp = timestamp,
        )

    // ── is_dry_run ───────────────────────────────────────────────────────

    @Test
    fun `is_dry_run returns true when dry run active`() {
        val ctx = ConditionContext(mr = makeMr(), dryRun = true)
        assertTrue(evaluator(ctx).evaluate("is_dry_run"))
    }

    @Test
    fun `is_dry_run returns false when dry run inactive`() {
        val ctx = ConditionContext(mr = makeMr(), dryRun = false)
        assertFalse(evaluator(ctx).evaluate("is_dry_run"))
    }

    // ── has_profile ──────────────────────────────────────────────────────

    @Test
    fun `has_profile returns true when profile set`() {
        val ctx = ConditionContext(mr = makeMr(), profileId = "strict")
        assertTrue(evaluator(ctx).evaluate("has_profile"))
    }

    @Test
    fun `has_profile returns false when no profile`() {
        val ctx = ConditionContext(mr = makeMr(), profileId = null)
        assertFalse(evaluator(ctx).evaluate("has_profile"))
    }

    // ── profile_is ───────────────────────────────────────────────────────

    @Test
    fun `profile_is returns true when profile matches`() {
        val ctx = ConditionContext(mr = makeMr(), profileId = "strict")
        assertTrue(evaluator(ctx).evaluate("""profile_is("strict")"""))
    }

    @Test
    fun `profile_is returns false when profile differs`() {
        val ctx = ConditionContext(mr = makeMr(), profileId = "minimal")
        assertFalse(evaluator(ctx).evaluate("""profile_is("strict")"""))
    }

    @Test
    fun `profile_is returns false when no profile`() {
        val ctx = ConditionContext(mr = makeMr(), profileId = null)
        assertFalse(evaluator(ctx).evaluate("""profile_is("strict")"""))
    }

    // ── module_configured ────────────────────────────────────────────────

    @Test
    fun `module_configured returns true when module in set`() {
        val ctx = ConditionContext(
            mr = makeMr(),
            configuredModuleIds = setOf("slack", "console"),
        )
        assertTrue(evaluator(ctx).evaluate("""module_configured("slack")"""))
    }

    @Test
    fun `module_configured returns false when module not in set`() {
        val ctx = ConditionContext(
            mr = makeMr(),
            configuredModuleIds = setOf("console"),
        )
        assertFalse(evaluator(ctx).evaluate("""module_configured("slack")"""))
    }

    // ── mr_age_hours ─────────────────────────────────────────────────────

    @Test
    fun `mr_age_hours greater than threshold returns true`() {
        // now = 1700000000, earliest commit at 1700000000 - 10800 (3 hours ago)
        val now = 1700000000L
        // Compute exact: 1700000000 - 3*3600 = 1699989200
        // 2023-11-14T18:13:20Z
        val exactThreeHoursAgo = "2023-11-14T18:13:20Z"
        val ctx = ConditionContext(
            mr = makeMr(commits = listOf(commit(exactThreeHoursAgo))),
            nowEpochSeconds = now,
        )
        assertTrue(evaluator(ctx).evaluate("mr_age_hours > 2"))
    }

    @Test
    fun `mr_age_hours returns false when below threshold`() {
        val now = 1700000000L
        // 1 hour ago: 1700000000 - 3600 = 1699996400
        val oneHourAgo = "2023-11-14T23:13:20Z"
        val ctx = ConditionContext(
            mr = makeMr(commits = listOf(commit(oneHourAgo))),
            nowEpochSeconds = now,
        )
        assertFalse(evaluator(ctx).evaluate("mr_age_hours > 2"))
    }

    @Test
    fun `mr_age_hours returns false when no timestamp available`() {
        val ctx = ConditionContext(
            mr = makeMr(commits = emptyList()),
            nowEpochSeconds = 1700000000L,
        )
        // age is 0 when no commits
        assertFalse(evaluator(ctx).evaluate("mr_age_hours > 0"))
    }

    // ── mr_age_days ──────────────────────────────────────────────────────

    @Test
    fun `mr_age_days greater than threshold returns true`() {
        val now = 1700000000L
        // 3 days ago: 1700000000 - 3*86400 = 1699740800
        // 2023-11-11T18:13:20Z
        val threeDaysAgo = "2023-11-11T18:13:20Z"
        val ctx = ConditionContext(
            mr = makeMr(commits = listOf(commit(threeDaysAgo))),
            nowEpochSeconds = now,
        )
        assertTrue(evaluator(ctx).evaluate("mr_age_days > 2"))
    }

    @Test
    fun `mr_age_days returns false when below threshold`() {
        val now = 1700000000L
        // 1 day ago: 1700000000 - 86400 = 1699913600
        // 2023-11-13T18:13:20Z
        val oneDayAgo = "2023-11-13T18:13:20Z"
        val ctx = ConditionContext(
            mr = makeMr(commits = listOf(commit(oneDayAgo))),
            nowEpochSeconds = now,
        )
        assertFalse(evaluator(ctx).evaluate("mr_age_days > 2"))
    }

    // ── last_commit_age_hours ────────────────────────────────────────────

    @Test
    fun `last_commit_age_hours greater than threshold returns true`() {
        val now = 1700000000L
        // oldest commit 5 hours ago, newest 3 hours ago
        // newest = 1700000000 - 3*3600 = 1699989200 -> 2023-11-14T18:13:20Z
        val oldCommit = commit("2023-11-14T15:13:20Z") // 5 hours ago
        val newCommit = commit("2023-11-14T18:13:20Z") // 3 hours ago
        val ctx = ConditionContext(
            mr = makeMr(commits = listOf(oldCommit, newCommit)),
            nowEpochSeconds = now,
        )
        // last (most recent) commit is 3 hours old
        assertTrue(evaluator(ctx).evaluate("last_commit_age_hours > 2"))
    }

    @Test
    fun `last_commit_age_hours returns false when recent commit`() {
        val now = 1700000000L
        // 30 minutes ago: 1700000000 - 1800 = 1699998200
        // 2023-11-14T23:43:20Z
        val recentCommit = commit("2023-11-14T23:43:20Z")
        val ctx = ConditionContext(
            mr = makeMr(commits = listOf(recentCommit)),
            nowEpochSeconds = now,
        )
        assertFalse(evaluator(ctx).evaluate("last_commit_age_hours > 2"))
    }

    @Test
    fun `last_commit_age_hours returns false when no now set`() {
        val ctx = ConditionContext(
            mr = makeMr(commits = listOf(commit("2023-11-14T18:13:20Z"))),
            nowEpochSeconds = 0L,
        )
        assertFalse(evaluator(ctx).evaluate("last_commit_age_hours > 0"))
    }

    @Test
    fun `last_commit_age_hours honors zone offsets and reads a zoneless timestamp as UTC`() {
        val now = 1700000000L // 2023-11-14T22:13:20Z
        val offset = ConditionContext(mr = makeMr(commits = listOf(commit("2023-11-15T00:13:20+02:00"))), nowEpochSeconds = now)
        assertTrue(evaluator(offset).evaluate("last_commit_age_hours == 0"))
        val zoneless = ConditionContext(mr = makeMr(commits = listOf(commit("2023-11-14T19:13:20"))), nowEpochSeconds = now)
        assertTrue(evaluator(zoneless).evaluate("last_commit_age_hours == 3"))
    }
}
