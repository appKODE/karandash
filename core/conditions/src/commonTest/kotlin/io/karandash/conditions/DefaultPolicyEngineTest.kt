package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.config.PolicyConfig
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultPolicyEngineTest {
    private fun makeMr(
        sourceBranch: String = "feature/my-branch",
        targetBranch: String = "main",
        title: String = "feat: add feature",
        author: String = "alice",
        draft: Boolean = false,
        labels: List<String> = emptyList(),
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = title,
            description = null,
            author = User(username = author, name = author),
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            state = MergeRequestState.OPENED,
            draft = draft,
            labels = labels,
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private val engine = DefaultPolicyEngine()
    private val emptyPredicates = MutablePredicateRegistry<ConditionContext>()

    @Test
    fun `empty policies returns empty list`() =
        runTest {
            val result = engine.evaluate(
                policies = emptyList(),
                context = ConditionContext(mr = makeMr()),
                predicates = emptyPredicates,
            )
            assertTrue(result.isEmpty())
        }

    @Test
    fun `policy decision is logged at debug`() =
        runTest {
            val debug = mutableListOf<String>()
            val logger = object : Logger {
                override val isDebugEnabled: Boolean get() = true

                override fun debug(message: String) {
                    debug.add(message)
                }

                override fun info(message: String) = Unit

                override fun warn(message: String) = Unit

                override fun error(message: String) = Unit
            }
            DefaultPolicyEngine(logger).evaluate(
                policies = listOf(PolicyConfig(name = "hotfix", condition = "branch_matches(\"^hotfix/\")", modules = listOf("m"))),
                context = ConditionContext(mr = makeMr()),
                predicates = emptyPredicates,
            )
            assertEquals(listOf("[policy] evaluate hotfix: not matched (modules=[m])"), debug)
        }

    @Test
    fun `configured max expression length rejects a longer condition`() =
        runTest {
            val policy = PolicyConfig(condition = "branch_matches(\"^feature/\")", modules = listOf("m"))
            val context = ConditionContext(mr = makeMr(sourceBranch = "feature/foo"))
            assertTrue(engine.evaluate(listOf(policy), context, emptyPredicates).single().matched)
            val bounded = DefaultPolicyEngine(NoOpLogger, null, maxExpressionLength = 10, maxNestingDepth = 50)
            assertFalse(bounded.evaluate(listOf(policy), context, emptyPredicates).single().matched)
        }

    @Test
    fun `configured max regex length caps a field regex`() =
        runTest {
            val policy = PolicyConfig(condition = "branch ~= \"^feature/${"o".repeat(60)}\"", modules = listOf("m"))
            val context = ConditionContext(mr = makeMr(sourceBranch = "feature/${"o".repeat(60)}"))
            assertTrue(
                DefaultPolicyEngine(
                    NoOpLogger,
                    null,
                    2_000,
                    50,
                    maxRegexLength = 500
                ).evaluate(listOf(policy), context, emptyPredicates).single().matched
            )
            val bounded = DefaultPolicyEngine(NoOpLogger, null, 2_000, 50, maxRegexLength = 50)
            assertFalse(bounded.evaluate(listOf(policy), context, emptyPredicates).single().matched)
        }

    @Test
    fun `boolean literal conditions match or never match`() =
        runTest {
            for ((condition, expected) in listOf("true" to true, " TRUE " to true, "false" to false, "False" to false)) {
                val policy = PolicyConfig(condition = condition, modules = listOf("m"))
                val result = engine.evaluate(listOf(policy), ConditionContext(mr = makeMr()), emptyPredicates)
                assertEquals(expected, result.single().matched, condition)
            }
        }

    @Test
    fun `matching branch condition produces matched policy`() =
        runTest {
            val policy = PolicyConfig(
                condition = "branch_matches(\"^feature/\")",
                modules = listOf("titleFormat"),
                enabled = true,
            )
            val result = engine.evaluate(
                policies = listOf(policy),
                context = ConditionContext(mr = makeMr(sourceBranch = "feature/foo")),
                predicates = emptyPredicates,
            )
            assertEquals(1, result.size)
            assertTrue(result[0].matched)
            assertEquals(listOf("titleFormat"), result[0].affectedModules)
            assertEquals(true, result[0].forcedEnabled)
        }

    @Test
    fun `non-matching condition produces unmatched policy`() =
        runTest {
            val policy = PolicyConfig(
                condition = "branch_matches(\"^hotfix/\")",
                modules = listOf("titleFormat"),
                enabled = false,
            )
            val result = engine.evaluate(
                policies = listOf(policy),
                context = ConditionContext(mr = makeMr(sourceBranch = "feature/bar")),
                predicates = emptyPredicates,
            )
            assertEquals(1, result.size)
            assertFalse(result[0].matched)
            assertNull(result[0].forcedEnabled)
            assertNull(result[0].forcedSeverity)
        }

    @Test
    fun `matched policy carries config overrides`() =
        runTest {
            val overrides = JsonObject(
                mapOf("titleFormat" to JsonObject(mapOf("maxLength" to JsonPrimitive(50)))),
            )
            val policy = PolicyConfig(
                condition = "branch_matches(\"^feature/\")",
                override = overrides,
            )
            val result = engine.evaluate(
                policies = listOf(policy),
                context = ConditionContext(mr = makeMr(sourceBranch = "feature/x")),
                predicates = emptyPredicates,
            )
            assertTrue(result[0].matched)
            assertEquals(overrides, result[0].configOverrides)
        }

    @Test
    fun `unmatched policy retains configOverrides for skip tracking`() =
        runTest {
            val overrides = JsonObject(
                mapOf("titleFormat" to JsonObject(mapOf("maxLength" to JsonPrimitive(50)))),
            )
            val policy = PolicyConfig(
                condition = "branch_matches(\"^hotfix/\")",
                override = overrides,
            )
            val result = engine.evaluate(
                policies = listOf(policy),
                context = ConditionContext(mr = makeMr(sourceBranch = "feature/x")),
                predicates = emptyPredicates,
            )
            assertFalse(result[0].matched)
            assertEquals(overrides, result[0].configOverrides)
        }

    @Test
    fun `malformed condition treated as non-matching`() =
        runTest {
            val policy = PolicyConfig(
                condition = "((( invalid expression",
                modules = listOf("titleFormat"),
            )
            val result = engine.evaluate(
                policies = listOf(policy),
                context = ConditionContext(mr = makeMr()),
                predicates = emptyPredicates,
            )
            assertEquals(1, result.size)
            assertFalse(result[0].matched)
        }

    @Test
    fun `multiple policies evaluated independently`() =
        runTest {
            val policies = listOf(
                PolicyConfig(condition = "branch_matches(\"^feature/\")", enabled = true),
                PolicyConfig(condition = "branch_matches(\"^hotfix/\")", enabled = false),
                PolicyConfig(condition = "is_draft", enabled = true),
            )
            val result = engine.evaluate(
                policies = policies,
                context = ConditionContext(mr = makeMr(sourceBranch = "feature/x", draft = true)),
                predicates = emptyPredicates,
            )
            assertEquals(3, result.size)
            assertTrue(result[0].matched)
            assertFalse(result[1].matched)
            assertTrue(result[2].matched)
        }

    @Test
    fun `custom predicates from registry are available`() =
        runTest {
            val registry = MutablePredicateRegistry<ConditionContext>()
            registry.register(
                object : ConditionPredicate<ConditionContext> {
                    override val name = "custom_check"
                    override val arity = 0
                    override val isBarePredicate = true

                    override fun evaluate(
                        args: List<String>,
                        context: ConditionContext,
                    ) = true
                },
            )
            val policy = PolicyConfig(condition = "custom_check")
            val result = engine.evaluate(
                policies = listOf(policy),
                context = ConditionContext(mr = makeMr()),
                predicates = registry,
            )
            assertTrue(result[0].matched)
        }

    @Test
    fun `severity override carried on matched policy`() =
        runTest {
            val policy = PolicyConfig(
                condition = "branch_matches(\"^feature/\")",
                severity = "warning",
            )
            val result = engine.evaluate(
                policies = listOf(policy),
                context = ConditionContext(mr = makeMr(sourceBranch = "feature/x")),
                predicates = emptyPredicates,
            )
            assertTrue(result[0].matched)
            assertEquals("warning", result[0].forcedSeverity)
        }
}
