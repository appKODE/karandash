package io.karandash.conditions

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/** concurrent registrations must not lose each other's entries. */
class ExpressionEvaluatorRegistryRaceTest {
    private class Named(
        override val name: String,
    ) : ConditionPredicate<Unit> {
        override val arity: Int = 0
        override val isBarePredicate: Boolean = true

        override fun evaluate(
            args: List<String>,
            context: Unit,
        ): Boolean = true
    }

    @Test
    fun `concurrent registrations keep every predicate`() {
        val evaluator = ExpressionEvaluator(Unit)
        val threads = 8
        val perThread = 300
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                repeat(perThread) { i ->
                    val name = "p_${t}_$i"
                    evaluator.registerPredicate(Named(name))
                    evaluator.markBarePredicate(name)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        val missing = (0 until threads).sumOf { t ->
            (0 until perThread).count { i ->
                runCatching { evaluator.evaluate("p_${t}_$i") }.getOrDefault(false).not()
            }
        }
        assertEquals(0, missing, "registrations lost: $missing")
    }

    private class AnyArity(
        override val name: String,
    ) : ConditionPredicate<Unit> {
        override val arity: Int = -1
        override val isBarePredicate: Boolean = true

        override fun evaluate(
            args: List<String>,
            context: Unit,
        ): Boolean = true
    }

    @Test
    fun `a registered bare predicate is never seen without its flag`() {
        val evaluator = ExpressionEvaluator(Unit)
        val total = 20_000
        val current = AtomicInteger(-1)
        val failure = AtomicReference<String?>(null)
        val reader = thread {
            while (current.get() < total - 1 && failure.get() == null) {
                val i = current.get()
                if (i < 0) continue
                val visible = runCatching { evaluator.evaluate("q_$i(x)") }.isSuccess
                if (visible && runCatching { evaluator.evaluate("q_$i") }.isFailure) failure.set("q_$i visible without bare flag")
            }
        }
        repeat(total) { i ->
            current.set(i)
            evaluator.registerAll(MutablePredicateRegistry<Unit>().also { it.register(AnyArity("q_$i")) })
        }
        reader.join()
        assertEquals(null, failure.get())
    }
}
