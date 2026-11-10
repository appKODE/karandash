package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleResultPredicatesTest {
    private val context = ConditionContext(
        mr = MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = null,
            author = User("a", "A"),
            sourceBranch = "f",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            labels = emptyList(),
            changes = emptyList(),
            commits = emptyList(),
            draft = false,
        ),
    )

    @Test
    fun `rule result predicates reflect the findings recorded per rule`() {
        val registry = MutablePredicateRegistry<ConditionContext>()
        RuleResultPredicates.register(
            registry,
            mapOf(
                "err" to listOf(Finding(Severity.WARNING, "w", "err"), Finding(Severity.ERROR, "e", "err")),
                "warn" to listOf(Finding(Severity.WARNING, "w", "warn")),
                "clean" to emptyList(),
            ),
        )
        val expected = mapOf(
            // rule id to (has findings, passed, failed)
            "err" to Triple(true, false, true),
            "warn" to Triple(true, true, false),
            "clean" to Triple(false, true, false),
            "missing" to Triple(false, true, false),
        )
        val evaluate = { name: String, args: List<String> -> checkNotNull(registry.get(name)).evaluate(args, context) }
        expected.forEach { (rule, outcome) ->
            val actual = Triple(
                evaluate("rule_has_findings", listOf(rule)),
                evaluate("rule_passed", listOf(rule)),
                evaluate("rule_failed", listOf(rule)),
            )
            assertEquals(outcome, actual, rule)
        }
        listOf("rule_has_findings", "rule_passed", "rule_failed").forEach { name ->
            assertEquals(false, evaluate(name, emptyList()), name)
        }
    }
}
