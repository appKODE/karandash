package io.karandash.config.pipeline

import io.karandash.api.InternalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.context.ConditionContext
import io.karandash.api.context.ConditionPredicate
import io.karandash.api.platform.PredicateRegistry
import io.karandash.config.Config
import io.karandash.config.LimitsConfig
import io.karandash.config.PolicyConfig
import io.karandash.config.RegexLimitsConfig
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.config.WhenConfig
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.typed.TypedRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** policy and rule `when` expressions are parsed at load against the run's real predicates. */
@OptIn(InternalKarandashApi::class)
class ConditionExpressionCheckTest {
    @Serializable
    class NoConfig

    private class TagRule : TypedRuleModule<NoConfig> {
        override val configSerializer = NoConfig.serializer()
        override val id = "tag-rule"
        override val name = "Tag Rule"
        override val version = "1.0"

        override suspend fun contributedRules() = setOf("tag-rule")

        override suspend fun initialize(context: PluginContext) = true

        override fun registerPredicates(registry: PredicateRegistry<ConditionContext>) {
            registry.register(
                object : ConditionPredicate<ConditionContext> {
                    override val name = "has_tag"
                    override val arity = 1

                    override fun evaluate(
                        args: List<String>,
                        context: ConditionContext,
                    ) = true
                },
            )
        }

        override suspend fun validateTyped(
            ruleId: String,
            mr: MergeRequest,
            config: NoConfig,
        ) = emptyList<Finding>()
    }

    private fun policy(expression: String) = PolicyConfig(condition = expression, name = "p")

    private suspend fun errors(
        config: Config,
        withTagRule: Boolean = true,
    ): List<String> {
        val registry = ModuleRegistryImpl()
        if (withTagRule) registry.register(TagRule())
        return ConditionExpressionCheck.errors(config, registry)
    }

    @Test
    fun `unknown predicate in a policy is reported with the policy name`() =
        runTest {
            val errors = errors(Config(policies = listOf(policy("is_draft && no_such_predicate(x)"))))

            assertEquals(1, errors.size, errors.toString())
            assertTrue("policy 'p'" in errors.single() && "no_such_predicate" in errors.single(), errors.single())
        }

    @Test
    fun `unknown predicate in a rule when is reported with the rule id`() =
        runTest {
            val rules = RulesConfig(ruleMeta = mapOf("branchName" to RuleExecutionMeta(`when` = WhenConfig("nope"))))

            val errors = errors(Config(rules = rules))

            assertEquals(1, errors.size, errors.toString())
            assertTrue("rule 'branchName'" in errors.single(), errors.single())
        }

    @Test
    fun `built-in field operators and rule result predicates are known`() =
        runTest {
            val expressions = listOf("title *= \"feat:*\"", "is_draft || rule_failed(x)", "branch ~= \"^f\"", "true")

            assertEquals(emptyList(), errors(Config(policies = expressions.map(::policy))))
        }

    @Test
    fun `module predicate has_tag is known once its plugin is registered and unknown without it`() =
        runTest {
            val config = Config(policies = listOf(policy("has_tag(x)")))

            assertEquals(emptyList(), errors(config, withTagRule = true))
            assertEquals(1, errors(config, withTagRule = false).size)
        }

    @Test
    fun `a field regex is capped by limits regex maxPatternLength`() =
        runTest {
            val longRegex = "^" + "a".repeat(80)
            val config = Config(policies = listOf(policy("branch ~= \"$longRegex\"")))

            assertEquals(emptyList(), errors(config))
            val capped = config.copy(limits = LimitsConfig(regex = RegexLimitsConfig(maxPatternLength = 50)))
            val error = errors(capped).single()
            assertTrue("81 > 50" in error, error)
        }
}
