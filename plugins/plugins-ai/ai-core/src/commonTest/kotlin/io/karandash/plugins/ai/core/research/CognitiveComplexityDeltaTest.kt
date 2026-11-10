package io.karandash.plugins.ai.core.research

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CognitiveComplexityDeltaTest {
    private fun change(
        path: String,
        diff: String?
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, diffContent = diff)

    @Test
    fun `no diff content returns empty list`() {
        val result = CognitiveComplexityDelta.compute(listOf(change("A.kt", null)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty changes returns empty list`() {
        val result = CognitiveComplexityDelta.compute(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `added if increases complexity`() {
        val diff = "+if (x > 0) {"
        val result = CognitiveComplexityDelta.compute(listOf(change("A.kt", diff)))
        assertEquals(1, result.size)
        assertTrue(result[0].addedComplexity > 0)
        assertTrue(result[0].increased)
    }

    @Test
    fun `added for loop increases complexity`() {
        val diff = "+for (i in list) {"
        val complexity = CognitiveComplexityDelta.computeComplexity(listOf("for (i in list) {"))
        assertTrue(complexity > 0)
    }

    @Test
    fun `removed control flow decreases delta`() {
        val diff = "-if (condition) {\n-    doSomething()\n-}"
        val result = CognitiveComplexityDelta.compute(listOf(change("A.kt", diff)))
        assertEquals(1, result.size)
        assertTrue(result[0].removedComplexity > 0)
        assertTrue(result[0].delta < 0, "Removing control flow should decrease delta")
        assertFalse(result[0].increased)
    }

    @Test
    fun `nesting increases weight`() {
        val flat = CognitiveComplexityDelta.computeComplexity(listOf("if (a) {"))
        val nested = CognitiveComplexityDelta.computeComplexity(
            listOf(
                "if (outer) {",
                "    if (inner) {",
            ),
        )
        assertTrue(nested > flat, "Nested control flow should have higher complexity: $nested vs $flat")
    }

    @Test
    fun `boolean operators contribute to complexity`() {
        val withBool = CognitiveComplexityDelta.computeComplexity(listOf("if (a && b || c) {"))
        val withoutBool = CognitiveComplexityDelta.computeComplexity(listOf("if (a) {"))
        assertTrue(withBool > withoutBool, "Boolean operators should increase complexity")
    }

    @Test
    fun `totalDelta sums across files`() {
        val changes = listOf(
            change("A.kt", "+if (x) {\n+for (i in y) {"),
            change("B.kt", "-if (z) {"),
        )
        val deltas = CognitiveComplexityDelta.compute(changes)
        val total = CognitiveComplexityDelta.totalDelta(deltas)
        val expected = deltas.sumOf { it.delta }
        assertEquals(expected, total)
    }

    @Test
    fun `totalDelta returns zero for empty list`() {
        assertEquals(0, CognitiveComplexityDelta.totalDelta(emptyList()))
    }

    @Test
    fun `path preserved in result`() {
        val diff = "+if (x) {"
        val result = CognitiveComplexityDelta.compute(listOf(change("src/Feature.kt", diff)))
        assertEquals("src/Feature.kt", result[0].path)
    }

    @Test
    fun `no diff lines produces zero complexity`() {
        val complexity = CognitiveComplexityDelta.computeComplexity(emptyList())
        assertEquals(0, complexity)
    }

    @Test
    fun `diff header lines ignored`() {
        val diff = "+++ b/A.kt\n--- a/A.kt\n+if (x) {"
        val result = CognitiveComplexityDelta.compute(listOf(change("A.kt", diff)))
        assertEquals(1, result.size)
        // Only the +if line should count; +++ line is filtered.
        assertTrue(result[0].addedComplexity > 0)
    }
}
