package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for environment and CI predicates registered by [EnvironmentPredicates].
 */
class EnvironmentPredicatesTest {
    private val baseMr = MergeRequest(
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

    // ── is_ci ────────────────────────────────────────────────────────────

    @Test
    fun `is_ci returns true when running in CI`() {
        val ctx = ConditionContext(mr = baseMr, isCI = true)
        assertTrue(evaluator(ctx).evaluate("is_ci"))
    }

    @Test
    fun `is_ci returns false when not in CI`() {
        val ctx = ConditionContext(mr = baseMr, isCI = false)
        assertFalse(evaluator(ctx).evaluate("is_ci"))
    }

    // ── is_gitlab_ci ─────────────────────────────────────────────────────

    @Test
    fun `is_gitlab_ci returns true for gitlab platform`() {
        val ctx = ConditionContext(mr = baseMr, ciPlatform = "gitlab")
        assertTrue(evaluator(ctx).evaluate("is_gitlab_ci"))
    }

    @Test
    fun `is_gitlab_ci returns false for github platform`() {
        val ctx = ConditionContext(mr = baseMr, ciPlatform = "github")
        assertFalse(evaluator(ctx).evaluate("is_gitlab_ci"))
    }

    @Test
    fun `is_gitlab_ci returns false when no platform`() {
        val ctx = ConditionContext(mr = baseMr, ciPlatform = null)
        assertFalse(evaluator(ctx).evaluate("is_gitlab_ci"))
    }

    // ── is_github_actions ────────────────────────────────────────────────

    @Test
    fun `is_github_actions returns true for github platform`() {
        val ctx = ConditionContext(mr = baseMr, ciPlatform = "github")
        assertTrue(evaluator(ctx).evaluate("is_github_actions"))
    }

    @Test
    fun `is_github_actions returns false for gitlab platform`() {
        val ctx = ConditionContext(mr = baseMr, ciPlatform = "gitlab")
        assertFalse(evaluator(ctx).evaluate("is_github_actions"))
    }

    // ── ci_platform ──────────────────────────────────────────────────────

    @Test
    fun `ci_platform returns true when platform matches`() {
        val ctx = ConditionContext(mr = baseMr, ciPlatform = "gitlab")
        assertTrue(evaluator(ctx).evaluate("""ci_platform("gitlab")"""))
    }

    @Test
    fun `ci_platform returns false when platform differs`() {
        val ctx = ConditionContext(mr = baseMr, ciPlatform = "github")
        assertFalse(evaluator(ctx).evaluate("""ci_platform("gitlab")"""))
    }

    @Test
    fun `ci_platform returns false when platform null`() {
        val ctx = ConditionContext(mr = baseMr, ciPlatform = null)
        assertFalse(evaluator(ctx).evaluate("""ci_platform("gitlab")"""))
    }

    // ── env_set ──────────────────────────────────────────────────────────

    @Test
    fun `env_set returns true when variable is set`() {
        val ctx = ConditionContext(
            mr = baseMr,
            env = { name -> if (name == "CI") "true" else null },
        )
        assertTrue(evaluator(ctx).evaluate("""env_set("CI")"""))
    }

    @Test
    fun `env_set returns false when variable not set`() {
        val ctx = ConditionContext(
            mr = baseMr,
            env = { null },
        )
        assertFalse(evaluator(ctx).evaluate("""env_set("CI")"""))
    }

    // ── env_equals ───────────────────────────────────────────────────────

    @Test
    fun `env_equals returns true when value matches`() {
        val ctx = ConditionContext(
            mr = baseMr,
            env = { name -> if (name == "CI") "true" else null },
        )
        assertTrue(evaluator(ctx).evaluate("""env_equals("CI", "true")"""))
    }

    @Test
    fun `env_equals returns false when value differs`() {
        val ctx = ConditionContext(
            mr = baseMr,
            env = { name -> if (name == "CI") "1" else null },
        )
        assertFalse(evaluator(ctx).evaluate("""env_equals("CI", "true")"""))
    }

    @Test
    fun `env_equals returns false when variable not set`() {
        val ctx = ConditionContext(
            mr = baseMr,
            env = { null },
        )
        assertFalse(evaluator(ctx).evaluate("""env_equals("CI", "true")"""))
    }

    // ── env_matches ──────────────────────────────────────────────────────

    @Test
    fun `env_matches returns true when regex matches value`() {
        val ctx = ConditionContext(
            mr = baseMr,
            env = { name -> if (name == "CI") "true" else null },
        )
        assertTrue(evaluator(ctx).evaluate("""env_matches("CI", "tr.*")"""))
    }

    @Test
    fun `env_matches returns false when regex does not match`() {
        val ctx = ConditionContext(
            mr = baseMr,
            env = { name -> if (name == "CI") "false" else null },
        )
        assertFalse(evaluator(ctx).evaluate("""env_matches("CI", "^tr")"""))
    }

    @Test
    fun `env_matches returns false when variable not set`() {
        val ctx = ConditionContext(
            mr = baseMr,
            env = { null },
        )
        assertFalse(evaluator(ctx).evaluate("""env_matches("CI", ".*")"""))
    }
}
