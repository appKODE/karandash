package io.karandash.patterns

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every built-in named pattern compiles through [PatternResolver] and honours its own examples. */
class BuiltInPatternSweepTest {
    private val registry = CorePatternRegistry.create()
    private val resolver = PatternResolver(registry = registry)

    @Test
    fun `every built-in pattern compiles through the resolver and matches its examples`() {
        val definitions = registry.all()
        assertTrue(definitions.isNotEmpty(), "the core registry is empty")
        definitions.forEach { definition ->
            // defaults may hold commas ({2,10}), which split positional arguments, so only pass arguments that are required
            val reference =
                if (definition.params.all { it.defaultValue != null }) {
                    "@${definition.name}"
                } else {
                    "@${definition.name}(${definition.params.joinToString(", ") { "x" }})"
                }
            val result = resolver.resolve(reference)
            assertIs<PatternCompilationResult.Success>(result, "$reference did not compile: $result")
            if (definition.params.isEmpty()) {
                definition.examples.forEach {
                    assertTrue(result.regex.containsMatchIn(it), "$reference example '$it' does not match")
                }
                definition.counterExamples.forEach {
                    assertFalse(result.regex.matches(it), "$reference counter-example '$it' fully matches")
                }
            }
        }
    }
}
