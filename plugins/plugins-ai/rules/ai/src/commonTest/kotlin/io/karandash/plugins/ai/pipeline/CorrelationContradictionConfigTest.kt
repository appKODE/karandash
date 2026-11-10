package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ai.correlation` and `ai.contradictions` as read from the `ai:` block. */
class CorrelationContradictionConfigTest {
    private val processor = AiFindingPostProcessor(NoOpLogger)

    private fun config(json: String) = Json.parseToJsonElement("""{"ai": $json}""")

    private fun finding(
        rule: String,
        message: String,
        line: Int = 10,
        severity: Severity = Severity.WARNING,
        confidence: Double? = null,
    ) = Finding(
        severity = severity,
        message = message,
        rule = rule,
        location = Location.FileRef("src/A.kt", line),
        metadata = confidence?.let { mapOf(AiFindingMetadataKeys.CONFIDENCE to it.toString()) }.orEmpty(),
    )

    private val review = finding("ai-review", "Null check missing here", severity = Severity.INFO, confidence = 0.9)
    private val bugs = finding("ai-bugs", "Unvalidated input reaches the query", confidence = 0.6)

    @Test
    fun `correlation block merges same-line findings from different analyzers`() {
        val result = processor.applyCorrelation(listOf(review, bugs), config("""{"correlation": {}}"""))

        val merged = result.single()
        assertEquals("ai-bugs", merged.rule)
        assertEquals("ai-bugs,ai-review", merged.metadata[AiFindingMetadataKeys.CORRELATED_SOURCES])
    }

    @Test
    fun `without a correlation block same-line findings stay apart`() {
        val result = processor.applyCorrelation(listOf(review, bugs), config("{}"))

        assertEquals(listOf(review, bugs), result)
    }

    @Test
    fun `correlation false skips merging and patterns`() {
        val result = processor.applyCorrelation(listOf(review, bugs), config("""{"correlation": false}"""))

        assertEquals(listOf(review, bugs), result)
    }

    @Test
    fun `a correlation pattern adds a meta-finding and upgrades the matched findings`() {
        val other = finding("ai-review", "Null check missing here", line = 40, severity = Severity.INFO)
        val cfg = config(
            """{"correlation": {"patterns": [
                {"name": "risky", "findings": ["ai-review", "ai-bugs"], "severity": "error"}
            ]}}""",
        )

        val result = processor.applyCorrelation(listOf(other, bugs.copy(location = Location.FileRef("B.kt", 1))), cfg)

        val meta = result.single { it.rule == "correlation:risky" }
        assertEquals(Severity.ERROR, meta.severity)
        assertEquals("Correlated findings: ai-review + ai-bugs", meta.message)
        assertTrue(result.filter { it.rule.startsWith("ai-") }.all { it.severity == Severity.ERROR })
    }

    @Test
    fun `a correlation pattern message replaces the generated meta-finding message`() {
        val other = finding("ai-review", "Null check missing here", line = 40, severity = Severity.INFO)
        val cfg = config(
            """{"correlation": {"patterns": [
                {"name": "risky", "findings": ["ai-review", "ai-bugs"], "message": "Review and bugs agree on this change"}
            ]}}""",
        )

        val result = processor.applyCorrelation(listOf(other, bugs.copy(location = Location.FileRef("B.kt", 1))), cfg)

        assertEquals("Review and bugs agree on this change", result.single { it.rule == "correlation:risky" }.message)
    }

    private fun whenConfig(expression: String) =
        config(
            """{"correlation": {"patterns": [
                {"name": "risky", "findings": ["ai-review", "ai-bugs"], "when": ${Json.encodeToString(String.serializer(), expression)}}
            ]}}""",
        )

    private val apart = listOf(review.copy(location = Location.FileRef("A.kt", 40)), bugs.copy(location = Location.FileRef("B.kt", 1)))

    @Test
    fun `a pattern runs only when its when condition holds for the MR`() {
        val hotfix = createTestMergeRequest(sourceBranch = "hotfix/login", labels = listOf("security"))
        val feature = createTestMergeRequest(sourceBranch = "feature/x")
        val cfg = whenConfig("label_exists(\"security\") && branch *= \"hotfix/*\"")

        assertTrue(processor.applyCorrelation(apart, cfg, hotfix).any { it.rule == "correlation:risky" })
        assertFalse(processor.applyCorrelation(apart, cfg, feature).any { it.rule == "correlation:risky" })
    }

    @Test
    fun `a malformed when condition or a missing MR never matches`() {
        val mr = createTestMergeRequest()
        assertFalse(processor.applyCorrelation(apart, whenConfig("no_such_predicate(1)"), mr).any { it.rule == "correlation:risky" })
        assertFalse(processor.applyCorrelation(apart, whenConfig("is_draft || !is_draft"), null).any { it.rule == "correlation:risky" })
    }

    private val add = finding("ai-review", "Add error handling", severity = Severity.ERROR, confidence = 0.4)
    private val remove = finding("ai-quality", "Remove the redundant error handling", line = 12, confidence = 0.9)

    @Test
    fun `contradictions default keeps the higher confidence finding`() {
        assertEquals(listOf(remove), processor.applyContradictionDetection(listOf(add, remove), config("{}")))
    }

    @Test
    fun `contradictions keep-higher-severity keeps the error`() {
        val cfg = config("""{"contradictions": {"resolution": "keep-higher-severity"}}""")

        assertEquals(listOf(add), processor.applyContradictionDetection(listOf(add, remove), cfg))
    }

    @Test
    fun `contradictions enabled false keeps both findings`() {
        val cfg = config("""{"contradictions": {"enabled": false}}""")

        assertEquals(listOf(add, remove), processor.applyContradictionDetection(listOf(add, remove), cfg))
    }

    @Test
    fun `boolean shorthand parses for both blocks`() {
        val ai = Json.parseToJsonElement("""{"correlation": true, "contradictions": false}""") as JsonObject
        val parsed = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(ai))

        assertEquals(true, parsed?.correlation?.enabled)
        assertFalse(parsed?.contradictions?.enabled ?: true)
        assertNull(
            AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(JsonObject(emptyMap())))?.correlation
        )
    }
}
