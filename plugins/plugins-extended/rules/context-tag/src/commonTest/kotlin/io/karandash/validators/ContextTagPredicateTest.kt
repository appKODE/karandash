package io.karandash.validators

import io.karandash.api.context.ConditionContext
import io.karandash.conditions.ExpressionEvaluator
import io.karandash.conditions.MutablePredicateRegistry
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the [ContextTagRuleModule.HasTagPredicate] registered via
 * [ContextTagRuleModule.registerPredicates].
 */
class ContextTagPredicateTest {
    private fun makeMr(title: String): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = title,
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/my-branch",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun evaluator(mr: MergeRequest): ExpressionEvaluator<ConditionContext> {
        val context = ConditionContext(mr = mr)
        val registry = MutablePredicateRegistry<ConditionContext>()
        ContextTagRuleModule().registerPredicates(registry)
        val ev = ExpressionEvaluator(context)
        ev.registerAll(registry)
        return ev
    }

    @Test
    fun `has_tag returns true when title contains matching tag`() {
        val ev = evaluator(makeMr(title = "[WIP] work in progress"))
        assertTrue(ev.evaluate("has_tag(WIP)"))
    }

    @Test
    fun `has_tag is case-insensitive`() {
        val ev = evaluator(makeMr(title = "[wip] work in progress"))
        assertTrue(ev.evaluate("has_tag(WIP)"))
    }

    @Test
    fun `has_tag returns false when tag absent`() {
        val ev = evaluator(makeMr(title = "feat: add feature"))
        assertFalse(ev.evaluate("has_tag(WIP)"))
    }

    @Test
    fun `has_tag returns false for partial bracket match`() {
        val ev = evaluator(makeMr(title = "WIPS] malformed"))
        assertFalse(ev.evaluate("has_tag(WIP)"))
    }

    @Test
    fun `has_tag works with multiple tags in title`() {
        val ev = evaluator(makeMr(title = "[RFC][BREAKING] redesign API"))
        assertTrue(ev.evaluate("has_tag(RFC)"))
        assertTrue(ev.evaluate("has_tag(BREAKING)"))
        assertFalse(ev.evaluate("has_tag(WIP)"))
    }

    @Test
    fun `has_tag treats regex metacharacters in tag literally`() {
        val ev = evaluator(makeMr(title = "[A.B] change"))
        assertTrue(ev.evaluate("has_tag(A.B)"))
        assertFalse(evaluator(makeMr(title = "[AXB] change")).evaluate("has_tag(A.B)"))
    }

    // Non-ASCII tags: `contains(ignoreCase = true)` folds Unicode case (as the JVM regex with
    // IGNORE_CASE did); native regex engines may differ - accepted.
    @Test
    fun `has_tag folds non-ASCII case`() {
        assertTrue(evaluator(makeMr(title = "[ÄNDERUNG] x")).evaluate("has_tag(änderung)"))
    }
}
