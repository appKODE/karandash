package io.karandash.conditions

import io.karandash.config.KarandashLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** other condition languages evaluate their boolean structure on this engine through [AtomExpression]. */
class AtomExpressionTest {
    @Test
    fun `leaves combine with the engine precedence and short-circuit`() {
        val reached = mutableListOf<Int>()
        val leaves = listOf(true, false, true).mapIndexed { index, value ->
            {
                reached += index
                value
            }
        }
        val (a, b, c) = (0..2).map(AtomExpression::leaf)

        assertTrue(AtomExpression.evaluate("$a || $b && $c", leaves))
        assertEquals(listOf(0), reached)
        assertFalse(AtomExpression.evaluate("!($a || $b) && $c", leaves))
    }

    @Test
    fun `leaves nested deeper than the engine limit fail`() {
        val depth = KarandashLimits.MAX_NESTING_DEPTH + 1
        val expression = "(".repeat(depth) + AtomExpression.leaf(0) + ")".repeat(depth)

        assertFailsWith<IllegalArgumentException> { AtomExpression.evaluate(expression, listOf { true }) }
    }
}
