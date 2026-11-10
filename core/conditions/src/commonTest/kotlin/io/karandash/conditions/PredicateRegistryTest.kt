package io.karandash.conditions

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PredicateRegistryTest {
    /** Test-local context type - no domain dependency. */
    @Serializable
    private data class TestContext(
        val value: String
    )

    private fun testPredicate(
        predicateName: String,
        predicateArity: Int = 1,
    ): ConditionPredicate<TestContext> =
        object : ConditionPredicate<TestContext> {
            override val name: String = predicateName
            override val arity: Int = predicateArity

            override fun evaluate(
                args: List<String>,
                context: TestContext
            ): Boolean = context.value == args.firstOrNull()
        }

    @Test
    fun `register and get returns predicate`() {
        val registry = MutablePredicateRegistry<TestContext>()
        val predicate = testPredicate("test_pred")
        registry.register(predicate)
        assertEquals(predicate, registry.get("test_pred"))
    }

    @Test
    fun `get returns null for unregistered predicate`() {
        val registry = MutablePredicateRegistry<TestContext>()
        assertNull(registry.get("nonexistent"))
    }

    @Test
    fun `exists returns true for registered predicate`() {
        val registry = MutablePredicateRegistry<TestContext>()
        registry.register(testPredicate("exists_pred"))
        assertTrue(registry.exists("exists_pred"))
    }

    @Test
    fun `exists returns false for unregistered predicate`() {
        val registry = MutablePredicateRegistry<TestContext>()
        assertFalse(registry.exists("nonexistent"))
    }

    @Test
    fun `all returns all registered predicates`() {
        val registry = MutablePredicateRegistry<TestContext>()
        registry.register(testPredicate("a"))
        registry.register(testPredicate("b"))
        registry.register(testPredicate("c"))
        val names = registry.all().map { it.name }.toSet()
        assertEquals(setOf("a", "b", "c"), names)
    }

    @Test
    fun `register replaces existing predicate with same name`() {
        val registry = MutablePredicateRegistry<TestContext>()
        val first = testPredicate("dup", predicateArity = 1)
        val second = testPredicate("dup", predicateArity = 2)
        registry.register(first)
        registry.register(second)
        assertEquals(1, registry.all().size)
        assertEquals(2, registry.get("dup")?.arity)
    }

    @Test
    fun `all returns empty list when no predicates registered`() {
        val registry = MutablePredicateRegistry<TestContext>()
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `ExpressionEvaluator registerAll reads metadata flags`() {
        val registry = MutablePredicateRegistry<TestContext>()
        val barePredicate = object : ConditionPredicate<TestContext> {
            override val name = "is_test"
            override val arity = 1
            override val isBarePredicate = true

            override fun evaluate(
                args: List<String>,
                context: TestContext
            ) = true
        }
        val compPredicate = object : ConditionPredicate<TestContext> {
            override val name = "count"
            override val arity = 2
            override val isComparisonPredicate = true

            override fun evaluate(
                args: List<String>,
                context: TestContext
            ) = true
        }
        registry.register(barePredicate)
        registry.register(compPredicate)

        val context = TestContext("test")
        val evaluator = ExpressionEvaluator(context)
        evaluator.registerAll(registry)

        // bare predicate should work without parentheses
        assertTrue(evaluator.evaluate("is_test"))
        // comparison predicate should work with operator syntax
        assertTrue(evaluator.evaluate("count > 0"))
    }
}
