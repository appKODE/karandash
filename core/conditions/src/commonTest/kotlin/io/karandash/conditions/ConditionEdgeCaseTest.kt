package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.api.context.ConditionPredicate
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionEdgeCaseTest {
    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "feat: x",
        description = "body",
        author = User(username = "alice", name = "Alice"),
        sourceBranch = "feature/x",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        labels = listOf("bug"),
        changes = listOf(
            FileChange("src/a.kt", ChangeType.ADDED, additions = 3),
            FileChange("src/b.kt", ChangeType.MODIFIED, additions = 1, deletions = 2),
            FileChange("old.kt", ChangeType.DELETED, deletions = 4),
        ),
        commits = emptyList(),
        draft = true,
    )

    private val contexts = listOf(
        ConditionContext(mr = mr, env = { if (it == "SET") "value" else null }, isCI = true, ciPlatform = "gitlab"),
        ConditionContext(mr = mr.copy(changes = emptyList(), labels = emptyList(), draft = false, description = null)),
    )

    private fun predicates(regexCacheFn: ((String) -> Regex)?): List<ConditionPredicate<ConditionContext>> {
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry, regexCacheFn)
        MrDataPredicates.registerAll(registry, regexCacheFn)
        EnvironmentPredicates.registerAll(registry, regexCacheFn)
        ConfigPredicates.registerAll(registry)
        FileMetricPredicates.registerAll(registry)
        return registry.all() + FieldMatchPredicates.forField("title", { it.mr.title }, regexCacheFn)
    }

    @Test
    fun `every predicate rejects missing or malformed arguments without throwing`() {
        val malformed = listOf(emptyList(), listOf("NOPE", "1"), listOf("GT", "x"), listOf("GT"))
        val loose = listOf(listOf("("), listOf("SET"), listOf("SET", "value"), listOf("x", "target"), listOf("GT", "0"), listOf("[z-a]"))
        listOf(null, { p: String -> Regex(Regex.escape(p)) }).forEach { cacheFn ->
            predicates(cacheFn).forEach { predicate ->
                contexts.forEach { context ->
                    if (predicate.isComparisonPredicate) {
                        malformed.forEach { args ->
                            assertFalse(predicate.evaluate(args, context), "${predicate.name}$args")
                        }
                    } else {
                        predicate.evaluate(emptyList(), context)
                    }
                    loose.forEach { args -> predicate.evaluate(args, context) }
                }
            }
        }
    }

    private fun evaluator(
        context: ConditionContext = contexts.first(),
        expander: ((String) -> String)? = null,
    ) = ExpressionEvaluator(
        context,
        patternExpander = expander,
        maxExpressionLength = 200,
        maxNestingDepth = 3,
        maxRegexLength = 10
    ).apply {
        registerAll(MutablePredicateRegistry<ConditionContext>().also { CommonPredicates.registerAll(it) })
        FieldMatchPredicates.forField("title", { it.mr.title }).forEach(::registerPredicate)
        markRegexPredicate("title_regex_matches")
        registerOperatorField("title", FieldMatchPredicates.operatorNames("title"))
    }

    @Test
    fun `boolean structure combines literals predicates and negation`() {
        val ev = evaluator()
        val cases = mapOf(
            "true" to true,
            "FALSE" to false,
            "(true)" to true,
            "(true) && (false)" to false,
            "false || is_draft" to true,
            "not(is_draft)" to false,
            "!false" to true,
            "change_count >= 3 && additions > 3 && deletions <= 6 && total_changes == 10 && change_count < 4" to true,
            "title *= \"feat*\"" to true,
            "title ~= '^feat'" to true,
            "title = \"feat: x\"" to true,
            "title = \"other\"" to false,
            "label_exists(bug)" to true,
        )
        cases.forEach { (expression, expected) -> assertEquals(expected, ev.evaluate(expression), expression) }
    }

    @Test
    fun `malformed expressions fail with a reason`() {
        val ev = evaluator()
        val illegalArgument = listOf(
            "x".repeat(201),
            "((((true))))",
            "title ~= \"aaaaaaaaaaaaaaaa\"",
            "title_regex_matches(aaaaaaaaaaaaaaaa)",
            "change_count(XX, 1)",
        )
        illegalArgument.forEach { expression -> assertFailsWith<IllegalArgumentException>(expression) { ev.evaluate(expression) } }
        val illegalState = listOf("unknown_thing", "nope(1)", "change_count ~ 3", "change_count > many")
        illegalState.forEach { expression -> assertFailsWith<IllegalStateException>(expression) { ev.evaluate(expression) } }
    }

    @Test
    fun `pattern arguments are expanded and invalid cached regexes never match`() {
        val expanded = mutableListOf<String>()
        val ev = evaluator(expander = { arg -> "^feat".also { expanded += arg } })

        assertTrue(ev.evaluate("title_matches(@cc)"))
        assertEquals(listOf("@cc"), expanded)
        assertFalse(ev.cachedRegex("[z-a]").containsMatchIn("anything"))
        assertTrue(ev.cachedRegex("a+").matches("aa"))
    }
}
