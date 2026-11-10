package io.karandash.validators

import io.karandash.api.context.ConditionContext
import io.karandash.conditions.ExpressionEvaluator
import io.karandash.conditions.MutablePredicateRegistry
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextTagRuleModuleTest {
    @Test
    fun `module is opt in and reads delimiter and allowed tags from json config`() =
        runTest {
            val module = ContextTagRuleModule()
            assertFalse(module.defaultEnabled)
            assertTrue(module.initialize(TestPluginContext()))
            val config =
                JsonObject(
                    mapOf(
                        "allowed" to JsonArray(listOf(JsonPrimitive("ios"), JsonPrimitive("core"))),
                        "delimiter" to JsonPrimitive("parens"),
                        "checkCommitTitles" to JsonPrimitive(false),
                    ),
                )

            assertTrue(module.validate(ContextTagRuleModule.RULE_ID, createTestMergeRequest(title = "(IOS) fix"), config).isEmpty())
            assertEquals(
                listOf("Add one of the allowed context tags to the MR title: `(ios)`, `(core)`."),
                module.validate(ContextTagRuleModule.RULE_ID, createTestMergeRequest(title = "[ios] fix"), config).map { it.suggestion },
            )
        }

    @Test
    fun `has tag predicate matches a bracketed tag case insensitively`() {
        fun evaluate(
            title: String,
            expression: String,
        ): Boolean {
            val registry = MutablePredicateRegistry<ConditionContext>()
            ContextTagRuleModule().registerPredicates(registry)
            val evaluator = ExpressionEvaluator(ConditionContext(mr = createTestMergeRequest(title = title)))
            evaluator.registerAll(registry)
            return evaluator.evaluate(expression)
        }

        assertTrue(evaluate("[WIP] draft", "has_tag('wip')"))
        assertFalse(evaluate("WIP draft", "has_tag('wip')"))
    }
}
