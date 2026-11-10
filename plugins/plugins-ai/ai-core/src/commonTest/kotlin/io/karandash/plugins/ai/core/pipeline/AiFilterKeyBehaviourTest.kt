package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ai.filter` keys that no other test sets to a non-default value. */
class AiFilterKeyBehaviourTest {
    private fun ai(
        message: String,
        confidence: String? = null,
        line: Int = 1,
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = "ai-review",
        location = Location.FileRef("A.kt", line),
        metadata = confidence?.let { mapOf("confidence" to it) } ?: emptyMap(),
    )

    @Test
    fun `filter minConfidence drops findings below the configured value`() {
        val findings = listOf(ai("mid", "0.5"), ai("high", "0.9", line = 2))
        assertEquals(2, AiFindingFilter(FilterConfig(minConfidence = 0.1)).filter(findings).size)
        assertEquals(listOf("high"), AiFindingFilter(FilterConfig(minConfidence = 0.8)).filter(findings).map { it.message })
    }

    @Test
    fun `filter minConfidenceByModel lowers the bar for matching models only`() {
        fun by(model: String) = ai(model, "0.45").let { it.copy(metadata = it.metadata + ("aiModel" to model)) }
        val config = FilterConfig(minConfidence = 0.6, minConfidenceByModel = mapOf("anthropic/*" to 0.4))
        val kept = AiFindingFilter(config).filter(listOf(by("anthropic/claude-sonnet-4.5"), by("openai/gpt-5.2")))
        assertEquals(listOf("anthropic/claude-sonnet-4.5"), kept.map { it.message })
    }

    @Test
    fun `filter deduplicate false keeps findings with the same fingerprint`() {
        val findings = listOf(ai("same"), ai("same"))
        assertEquals(1, AiFindingFilter(FilterConfig(deduplicate = true)).filter(findings).size)
        assertEquals(2, AiFindingFilter(FilterConfig(deduplicate = false)).filter(findings).size)
    }
}
