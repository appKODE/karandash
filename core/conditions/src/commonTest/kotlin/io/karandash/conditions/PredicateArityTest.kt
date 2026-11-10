package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** the parser enforces [ConditionPredicate.arity] for function-call predicates. */
class PredicateArityTest {
    private val evaluator =
        ExpressionEvaluator(
            ConditionContext(
                mr = MergeRequest(
                    id = "1",
                    iid = 1,
                    title = "feat: x",
                    description = null,
                    author = User(username = "alice", name = "Alice"),
                    sourceBranch = "feature/x",
                    targetBranch = "main",
                    state = MergeRequestState.OPENED,
                    labels = listOf("a,b"),
                    changes = emptyList(),
                    commits = emptyList(),
                    approvals = emptyList(),
                    reviewers = emptyList(),
                    draft = false,
                ),
            ),
        ).apply { registerAll(MutablePredicateRegistry<ConditionContext>().also { CommonPredicates.registerAll(it) }) }

    @Test
    fun `an extra argument to a fixed-arity predicate is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { evaluator.evaluate("label_exists(a, b)") }
        assertTrue("takes 1 argument" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a regex predicate split by an unquoted comma is rejected`() {
        assertFailsWith<IllegalArgumentException> { evaluator.evaluate("title_matches(^feat{1,3})") }
    }

    @Test
    fun `a quoted comma is one argument and variable arity takes any count`() {
        assertTrue(evaluator.evaluate("label_exists(\"a,b\")"))
        assertTrue(evaluator.evaluate("branch_matches(\"^feature\", source)"))
        assertTrue(evaluator.evaluate("change_count(LT, 1)"))
    }
}
