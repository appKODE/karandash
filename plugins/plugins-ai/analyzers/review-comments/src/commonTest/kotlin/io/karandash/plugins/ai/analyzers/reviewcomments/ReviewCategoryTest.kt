package io.karandash.plugins.ai.analyzers.reviewcomments

import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Review-comments speaks a fixed category set; legacy and unknown categories are normalized at parse time. */
class ReviewCategoryTest {
    private val module = ReviewCommentsAnalyzerModule()

    private fun parse(raw: String) =
        module
            .parseResponse(
                """{"comments": [{"file": "src/Main.kt", "line": 1, "category": "$raw", "severity": "warning", "message": "m"}]}""",
                "test-model",
                10,
            ).single()

    private fun categoryOf(raw: String): String? = parse(raw).metadata[AiFindingMetadataKeys.CATEGORY]

    @Test
    fun `fixed categories pass through in upper case`() {
        for (category in listOf("BUG", "SEC", "PERF", "RACE", "LEAK", "DATA", "QUESTION")) {
            assertEquals(category, categoryOf(category))
        }
        assertEquals("SEC", categoryOf("sec"))
    }

    @Test
    fun `legacy categories map onto the fixed set`() {
        assertEquals("BUG", categoryOf("bug_risk"))
        assertEquals("SEC", categoryOf("security"))
        assertEquals("PERF", categoryOf("performance"))
        assertEquals("QUESTION", categoryOf("style"))
        assertEquals("QUESTION", categoryOf("TEST"))
    }

    @Test
    fun `unknown category becomes QUESTION and the finding is kept`() {
        assertEquals("QUESTION", categoryOf("made_up"))
    }

    @Test
    fun `a QUESTION is always reported at INFO`() {
        assertEquals(Severity.INFO, parse("QUESTION").severity)
    }

    @Test
    fun `prompts list exactly the fixed set`() {
        val set = "[BUG, SEC, PERF, RACE, LEAK, DATA, QUESTION]"
        assertTrue(module.defaultPrompt.contains(set), module.defaultPrompt)
        assertTrue("bug_risk" !in module.defaultPrompt)
    }
}
