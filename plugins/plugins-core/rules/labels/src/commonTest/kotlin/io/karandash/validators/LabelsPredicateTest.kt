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
 * Tests for the `label_exists` predicate registered by
 * [LabelsRuleModule.registerPredicates].
 */
class LabelsPredicateTest {
    private fun makeMr(labels: List<String> = emptyList()): MergeRequest =
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
            labels = labels,
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun evaluator(mr: MergeRequest): ExpressionEvaluator<ConditionContext> {
        val context = ConditionContext(mr = mr)
        val registry = MutablePredicateRegistry<ConditionContext>()
        LabelsRuleModule().registerPredicates(registry)
        val ev = ExpressionEvaluator(context)
        ev.registerAll(registry)
        return ev
    }

    @Test
    fun `label_exists returns true when label present`() {
        assertTrue(evaluator(makeMr(labels = listOf("bug", "urgent"))).evaluate("label_exists('bug')"))
    }

    @Test
    fun `label_exists returns false when label absent`() {
        assertFalse(evaluator(makeMr(labels = listOf("feature"))).evaluate("label_exists('bug')"))
    }

    @Test
    fun `label_exists returns false on empty labels`() {
        assertFalse(evaluator(makeMr()).evaluate("label_exists('bug')"))
    }
}
