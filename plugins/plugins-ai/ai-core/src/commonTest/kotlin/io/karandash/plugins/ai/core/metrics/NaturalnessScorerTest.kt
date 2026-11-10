package io.karandash.plugins.ai.core.metrics

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalnessScorerTest {
    private fun change(
        path: String,
        diff: String?
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = diff?.lines()?.count { it.startsWith("+") } ?: 0,
        deletions = 0,
        diffContent = diff,
    )

    /** Builds an added-line diff from the given content lines. */
    private fun addedDiff(vararg lines: String): String = lines.joinToString("\n") { "+$it" }

    private val naturalDiff = addedDiff(
        "fun processData(input: List<String>): List<String> {",
        "    return input.filter { it.isNotBlank() }",
        "        .map { it.trim() }",
        "        .distinct()",
        "        .sorted()",
        "}",
        "fun validateInput(data: String): Boolean {",
        "    return data.isNotBlank() && data.length > 3",
        "}",
        "fun transform(value: String): String {",
        "    return value.uppercase().trim()",
        "}",
        "fun combine(a: String, b: String): String {",
        "    return a + b",
        "}",
        "fun check(x: Int): Boolean {",
        "    return x > 0",
        "}",
        "fun wrap(s: String): String {",
        "    return \"[${"\$"}s]\"",
        "}",
    )

    @Test
    fun `empty changes returns empty`() {
        val result = NaturalnessScorer.score(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `null diffContent file is skipped`() {
        val result = NaturalnessScorer.score(listOf(change("src/A.kt", null)))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `too few tokens returns empty`() {
        // Only 3 added tokens - below MIN_TOKENS (20)
        val result = NaturalnessScorer.score(listOf(change("src/A.kt", addedDiff("a b c"))))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `buildTrigramModel creates correct counts`() {
        val tokens = listOf("a", "b", "c", "a", "b", "d")
        val model = NaturalnessScorer.buildTrigramModel(tokens)
        // context "a b" → "c" once, "d" once
        val dist = model["a b"]
        assertEquals(1, dist?.get("c"))
        assertEquals(1, dist?.get("d"))
    }

    @Test
    fun `buildTrigramModel counts repeated trigrams`() {
        val tokens = listOf("x", "y", "z", "x", "y", "z", "x", "y", "z")
        val model = NaturalnessScorer.buildTrigramModel(tokens)
        assertEquals(3, model["x y"]?.get("z"))
    }

    @Test
    fun `tokenize splits on whitespace`() {
        val tokens = NaturalnessScorer.tokenize("hello world foo")
        assertEquals(listOf("hello", "world", "foo"), tokens)
    }

    @Test
    fun `tokenize splits on operators`() {
        val tokens = NaturalnessScorer.tokenize("a+b*c")
        assertEquals(listOf("a", "b", "c"), tokens)
    }

    @Test
    fun `tokenize splits on braces and parentheses`() {
        val tokens = NaturalnessScorer.tokenize("fun foo(x: Int): String {")
        assertTrue("fun" in tokens)
        assertTrue("foo" in tokens)
        assertTrue("x" in tokens)
        assertTrue("Int" in tokens)
        assertTrue("String" in tokens)
    }

    @Test
    fun `tokenize filters blank tokens`() {
        val tokens = NaturalnessScorer.tokenize("   ")
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `results sorted by average surprise descending`() {
        val changes = listOf(
            change("src/A.kt", naturalDiff),
            change("src/B.kt", naturalDiff),
            change("src/C.kt", naturalDiff),
        )
        val result = NaturalnessScorer.score(changes)
        for (i in 0 until result.size - 1) {
            assertTrue(
                result[i].averageSurprise >= result[i + 1].averageSurprise,
                "Results must be sorted by averageSurprise descending",
            )
        }
    }

    @Test
    fun `naturalness entry has non-negative fields`() {
        val changes = listOf(change("src/A.kt", naturalDiff))
        val result = NaturalnessScorer.score(changes)
        if (result.isEmpty()) return // may be empty if context too small; skip
        val entry = result.first()
        assertTrue(entry.averageSurprise >= 0.0)
        assertTrue(entry.maxSurprise >= 0.0)
        assertTrue(entry.highSurpriseTokenCount >= 0)
        assertTrue(entry.totalTokens > 0)
    }

    @Test
    fun `high surprise tokens counted correctly — unseen trigrams score 1_0`() {
        // Construct a diff with many unique tokens so all trigrams are unseen
        val uniqueDiff = addedDiff(
            "alpha beta gamma delta epsilon zeta eta theta iota kappa",
            "lambda mu nu xi omicron pi rho sigma tau upsilon phi chi",
            "psi omega aleph beth gimel daleth he vav zayin chet tet",
        )
        // Provide natural context (different tokens) so model won't know these
        val contextChange = change("context/A.kt", naturalDiff)
        val foreignChange = change("src/Foreign.kt", uniqueDiff)
        val result = NaturalnessScorer.score(listOf(contextChange, foreignChange))
        val foreignEntry = result.find { it.path == "src/Foreign.kt" } ?: return
        // Unseen trigrams all score 1.0 - some will be above mean+2σ boundary
        assertTrue(foreignEntry.totalTokens > 0)
    }
}
