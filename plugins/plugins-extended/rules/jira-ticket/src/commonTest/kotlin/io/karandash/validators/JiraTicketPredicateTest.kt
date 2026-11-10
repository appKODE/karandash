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
 * Tests for the [JiraTicketRuleModule.HasTicketPredicate] registered via
 * [JiraTicketRuleModule.registerPredicates].
 */
class JiraTicketPredicateTest {
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
        JiraTicketRuleModule().registerPredicates(registry)
        val ev = ExpressionEvaluator(context)
        ev.registerAll(registry)
        return ev
    }

    @Test
    fun `has_ticket returns true when title contains matching ticket`() {
        val ev = evaluator(makeMr(title = "feat: [MAPS-123] add map feature"))
        assertTrue(ev.evaluate("has_ticket(MAPS)"))
    }

    @Test
    fun `has_ticket returns true for ticket at start of title`() {
        val ev = evaluator(makeMr(title = "PROJ-456 fix bug"))
        assertTrue(ev.evaluate("has_ticket(PROJ)"))
    }

    @Test
    fun `has_ticket is case-insensitive for project key`() {
        val ev = evaluator(makeMr(title = "maps-789 update docs"))
        assertTrue(ev.evaluate("has_ticket(MAPS)"))
    }

    @Test
    fun `has_ticket returns false when no matching ticket`() {
        val ev = evaluator(makeMr(title = "feat: add feature"))
        assertFalse(ev.evaluate("has_ticket(MAPS)"))
    }

    @Test
    fun `has_ticket returns false when different project`() {
        val ev = evaluator(makeMr(title = "OTHER-123 fix"))
        assertFalse(ev.evaluate("has_ticket(MAPS)"))
    }

    @Test
    fun `has_ticket requires digit suffix`() {
        val ev = evaluator(makeMr(title = "MAPS-abc no digits"))
        assertFalse(ev.evaluate("has_ticket(MAPS)"))
    }

    @Test
    fun `has_ticket does not match when project key is a suffix of a longer word`() {
        val ev = evaluator(makeMr(title = "XPROJ-1 fix"))
        assertFalse(ev.evaluate("has_ticket(PROJ)"))
    }

    @Test
    fun `has_ticket does not match non-digit ticket number`() {
        val ev = evaluator(makeMr(title = "PROJ-x fix"))
        assertFalse(ev.evaluate("has_ticket(PROJ)"))
    }

    @Test
    fun `has_ticket gives same answers on repeated evaluation with cached pattern`() {
        repeat(2) {
            assertTrue(evaluator(makeMr(title = "proj-12 fix")).evaluate("has_ticket(proj)"))
            assertFalse(evaluator(makeMr(title = "XPROJ-12 fix")).evaluate("has_ticket(PROJ)"))
        }
    }
}
