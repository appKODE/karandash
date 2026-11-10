package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.support.mapToJsonElement
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.sdk.Logger
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiFindingFilterTest {
    private val defaultConfig = FilterConfig()

    private class CapturingLogger : Logger {
        val messages = mutableListOf<Pair<String, String>>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            messages.add("debug" to message)
        }

        override fun info(message: String) {
            messages.add("info" to message)
        }

        override fun warn(message: String) {
            messages.add("warn" to message)
        }

        override fun error(message: String) {
            messages.add("error" to message)
        }
    }

    @Test
    fun `deduplicates findings with same fingerprint keeping highest severity`() {
        val findings = listOf(
            Finding(
                severity = Severity.INFO,
                message = "Same issue on line 10",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 10),
            ),
            Finding(
                severity = Severity.ERROR,
                message = "Same issue on line 10",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 10),
            ),
        )

        val result = AiFindingFilter(defaultConfig).filter(findings)

        assertEquals(1, result.size)
        assertEquals(Severity.ERROR, result[0].severity)
    }

    @Test
    fun `dedup keeps the more severe twin and the input order`() {
        val at = Location.FileRef("src/A.kt", 10)
        val findings = listOf(
            Finding(severity = Severity.INFO, message = "first", rule = "ai-review", location = Location.FileRef("src/B.kt", 1)),
            Finding(severity = Severity.WARNING, message = "twin", rule = "ai-review", location = at),
            Finding(severity = Severity.WARNING, message = "lint", rule = "lint-rule"),
            Finding(severity = Severity.ERROR, message = "twin", rule = "ai-review", location = at),
            Finding(severity = Severity.INFO, message = "last", rule = "ai-review", location = Location.FileRef("src/C.kt", 1)),
        )

        val result = AiFindingFilter(defaultConfig).filter(findings)

        assertEquals(listOf("first", "lint", "twin", "last"), result.map { it.message })
        assertEquals(Severity.ERROR, result[2].severity)
    }

    @Test
    fun `removes AI findings below confidence threshold`() {
        val findings = listOf(
            Finding(
                severity = Severity.WARNING,
                message = "Low confidence",
                rule = "ai-code-quality",
                metadata = mapOf("confidence" to "0.1"),
            ),
            Finding(
                severity = Severity.WARNING,
                message = "High confidence",
                rule = "ai-code-quality",
                metadata = mapOf("confidence" to "0.8"),
            ),
            Finding(
                severity = Severity.WARNING,
                message = "No confidence metadata",
                rule = "ai-code-quality",
            ),
        )

        val result = AiFindingFilter(defaultConfig).filter(findings)

        assertEquals(2, result.size)
        assertTrue(result.none { it.message == "Low confidence" })
        assertTrue(result.any { it.message == "High confidence" })
        assertTrue(result.any { it.message == "No confidence metadata" })
    }

    @Test
    fun `caps findings per rule keeping highest severity`() {
        val config = FilterConfig(maxPerRule = 2)
        val findings = listOf(
            Finding(severity = Severity.INFO, message = "Info 1", rule = "ai-review"),
            Finding(severity = Severity.ERROR, message = "Error 1", rule = "ai-review"),
            Finding(severity = Severity.WARNING, message = "Warn 1", rule = "ai-review"),
        )

        val result = AiFindingFilter(config).filter(findings)
        val aiResults = result.filter { it.rule == "ai-review" }

        assertEquals(2, aiResults.size)
        assertTrue(aiResults.any { it.severity == Severity.ERROR })
        assertTrue(aiResults.any { it.severity == Severity.WARNING })
    }

    @Test
    fun `a multi-line code-suggestion twin follows its comment through the per-file cap and is not counted`() {
        val meta = mapOf(AiFindingMetadataKeys.AI_MODEL to "m")
        val comment = Finding(Severity.ERROR, "Null deref", AiRuleIds.REVIEW_COMMENTS, Location.FileRef("src/A.kt", 5), metadata = meta)
        val other = Finding(Severity.INFO, "Naming", AiRuleIds.REVIEW_COMMENTS, Location.FileRef("src/A.kt", 9), metadata = meta)
        val twin = Finding(
            Severity.INFO,
            "Guard it",
            AiRuleIds.CODE_SUGGESTIONS,
            Location.FileRef("src/A.kt", 5, endLine = 7),
            metadata = meta,
        )

        val result = AiFindingFilter(FilterConfig(maxPerFile = 1)).filter(listOf(comment, other, twin))

        assertEquals(listOf("Null deref", "Guard it"), result.map { it.message })
    }

    @Test
    fun `caps findings per file keeping highest severity`() {
        val config = FilterConfig(maxPerFile = 1)
        val findings = listOf(
            Finding(
                severity = Severity.INFO,
                message = "Info finding",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 1),
            ),
            Finding(
                severity = Severity.ERROR,
                message = "Error finding",
                rule = "ai-code-quality",
                location = Location.FileRef("src/A.kt", 5),
            ),
        )

        val result = AiFindingFilter(config).filter(findings)
        val aiResults = result.filter { it.rule.startsWith("ai-") }

        assertEquals(1, aiResults.size)
        assertEquals(Severity.ERROR, aiResults[0].severity)
    }

    @Test
    fun `removes findings on deleted files`() {
        val findings = listOf(
            Finding(
                severity = Severity.WARNING,
                message = "Finding on deleted file",
                rule = "ai-review",
                location = Location.FileRef("src/Deleted.kt", 10),
            ),
            Finding(
                severity = Severity.WARNING,
                message = "Finding on existing file",
                rule = "ai-review",
                location = Location.FileRef("src/Existing.kt", 5),
            ),
            Finding(
                severity = Severity.WARNING,
                message = "Finding without location",
                rule = "ai-review",
            ),
        )

        val result = AiFindingFilter(defaultConfig).filter(
            findings,
            deletedFiles = setOf("src/Deleted.kt"),
        )

        assertEquals(2, result.size)
        assertTrue(result.none { it.message == "Finding on deleted file" })
    }

    @Test
    fun `non-AI findings are not affected by confidence or caps`() {
        val config = FilterConfig(maxPerRule = 1, minConfidence = 0.9)
        val findings = listOf(
            Finding(severity = Severity.INFO, message = "Non-AI 1", rule = "title-format"),
            Finding(severity = Severity.INFO, message = "Non-AI 2", rule = "title-format"),
            Finding(
                severity = Severity.INFO,
                message = "Non-AI with confidence",
                rule = "title-format",
                metadata = mapOf("confidence" to "0.1"),
            ),
        )

        val result = AiFindingFilter(config).filter(findings)
        assertEquals(3, result.size)
    }

    @Test
    fun `capPerFile does not drop findings with no file location`() {
        val config = FilterConfig(maxPerFile = 1)
        val findings = listOf(
            Finding(
                severity = Severity.INFO,
                message = "Summary finding with no location",
                rule = "mr-summary",
            ),
            Finding(
                severity = Severity.ERROR,
                message = "File finding A",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 1),
            ),
            Finding(
                severity = Severity.INFO,
                message = "File finding B",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 5),
            ),
        )

        val result = AiFindingFilter(config).filter(findings)

        // Summary finding (no location) must survive the per-file cap
        assertTrue(result.any { it.rule == "mr-summary" })
        // Per-file cap of 1 on src/A.kt: only the ERROR survives
        val fileFindings = result.filter { it.rule == "ai-review" }
        assertEquals(1, fileFindings.size)
        assertEquals(Severity.ERROR, fileFindings[0].severity)
    }

    @Test
    fun `FilterConfig deserialization parses camelCase keys`() {
        val rawCamel = mapOf<String, Any?>(
            "maxPerRule" to 7,
            "maxPerFile" to 4,
            "minConfidence" to 0.2,
            "deduplicate" to false,
        )
        val config = KarandashJson.Default.decodeFromJsonElement<FilterConfig>(mapToJsonElement(rawCamel))
        assertEquals(7, config.maxPerRule)
        assertEquals(4, config.maxPerFile)
        assertEquals(0.2, config.minConfidence)
        assertEquals(false, config.deduplicate)
    }

    @Test
    fun `FilterConfig defaults are applied for empty input`() {
        val config = KarandashJson.Default.decodeFromJsonElement<FilterConfig>(mapToJsonElement(emptyMap<String, Any?>()))
        assertEquals(FilterConfig.DEFAULT_MAX_PER_RULE, config.maxPerRule)
        assertEquals(FilterConfig.DEFAULT_MAX_PER_FILE, config.maxPerFile)
        assertEquals(FilterConfig.DEFAULT_MIN_CONFIDENCE, config.minConfidence)
        assertEquals(true, config.deduplicate)
    }

    // ── Evidence anchoring ────────────────────────────────────────────────

    @Test
    fun `removes AI findings referencing files not in MR changes`() {
        val findings = listOf(
            Finding(
                severity = Severity.WARNING,
                message = "Issue in changed file",
                rule = "ai-review",
                location = Location.FileRef("src/Changed.kt", 10),
            ),
            Finding(
                severity = Severity.WARNING,
                message = "Hallucinated file reference",
                rule = "ai-review",
                location = Location.FileRef("src/DoesNotExist.kt", 5),
            ),
            Finding(
                severity = Severity.WARNING,
                message = "Finding without location",
                rule = "ai-review",
            ),
        )

        val result = AiFindingFilter(defaultConfig).filter(
            findings,
            changedFiles = setOf("src/Changed.kt"),
        )

        assertEquals(2, result.size)
        assertTrue(result.any { it.message == "Issue in changed file" })
        assertTrue(result.any { it.message == "Finding without location" })
        assertTrue(result.none { it.message == "Hallucinated file reference" })
    }

    @Test
    fun `evidence anchoring does not affect non-AI findings`() {
        val findings = listOf(
            Finding(
                severity = Severity.WARNING,
                message = "Non-AI finding on unknown file",
                rule = "title-format",
                location = Location.FileRef("src/Unknown.kt", 1),
            ),
        )

        val result = AiFindingFilter(defaultConfig).filter(
            findings,
            changedFiles = setOf("src/Other.kt"),
        )

        assertEquals(1, result.size)
    }

    // ── Line number validation ────────────────────────────────────────────

    @Test
    fun `drops AI findings with line numbers outside diff range`() {
        val findings = listOf(
            Finding(
                severity = Severity.ERROR,
                message = "Valid line finding",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 10),
            ),
            Finding(
                severity = Severity.ERROR,
                message = "Invalid line finding",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 999),
            ),
        )

        val diffLines = mapOf("src/A.kt" to setOf(5, 10, 15, 20))

        val result = AiFindingFilter(defaultConfig).filter(
            findings,
            diffLineRanges = diffLines,
        )

        assertEquals(1, result.size)
        val valid = result.single()
        assertEquals("Valid line finding", valid.message)
        assertEquals(Severity.ERROR, valid.severity)
        assertTrue(result.none { it.message.contains("Invalid line finding") })
    }

    @Test
    fun `line validation keeps findings with no line number`() {
        val findings = listOf(
            Finding(
                severity = Severity.WARNING,
                message = "No line number",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt"),
            ),
        )

        val result = AiFindingFilter(defaultConfig).filter(
            findings,
            diffLineRanges = mapOf("src/A.kt" to setOf(1, 2, 3)),
        )

        assertEquals(1, result.size)
        assertEquals(Severity.WARNING, result[0].severity)
    }

    @Test
    fun `line validation keeps findings for files not in diff ranges map`() {
        val findings = listOf(
            Finding(
                severity = Severity.WARNING,
                message = "File not in range map",
                rule = "ai-review",
                location = Location.FileRef("src/Other.kt", 50),
            ),
        )

        val result = AiFindingFilter(defaultConfig).filter(
            findings,
            diffLineRanges = mapOf("src/A.kt" to setOf(1, 2, 3)),
        )

        assertEquals(1, result.size)
        assertEquals(Severity.WARNING, result[0].severity)
    }

    // ── Logging tests ────────────────────────────────────────────────────

    @Test
    fun `logs confidence filter decisions`() {
        val logger = CapturingLogger()
        val findings = listOf(
            Finding(
                severity = Severity.WARNING,
                message = "Low confidence finding",
                rule = "ai-review",
                metadata = mapOf("confidence" to "0.1"),
            ),
            Finding(
                severity = Severity.WARNING,
                message = "High confidence finding",
                rule = "ai-review",
                metadata = mapOf("confidence" to "0.8"),
            ),
        )

        AiFindingFilter(defaultConfig, logger).filter(findings)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("[confidence]") })
        assertTrue(debugMessages.any { it.contains("dropped") && it.contains("0.1") })
    }

    @Test
    fun `logs dedup filter decisions`() {
        val logger = CapturingLogger()
        val findings = listOf(
            Finding(
                severity = Severity.INFO,
                message = "Dup 1",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 10),
            ),
            Finding(
                severity = Severity.ERROR,
                message = "Dup 2",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 10),
            ),
        )

        AiFindingFilter(defaultConfig, logger).filter(findings)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("[dedup]") && it.contains("1 duplicate") })
    }

    @Test
    fun `logs per-file cap decisions`() {
        val logger = CapturingLogger()
        val config = FilterConfig(maxPerFile = 1)
        val findings = listOf(
            Finding(
                severity = Severity.INFO,
                message = "Finding 1",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 1),
            ),
            Finding(
                severity = Severity.ERROR,
                message = "Finding 2",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 5),
            ),
        )

        AiFindingFilter(config, logger).filter(findings)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("[per-file cap]") })
    }

    @Test
    fun `logs anchor filter decisions`() {
        val logger = CapturingLogger()
        val findings = listOf(
            Finding(
                severity = Severity.WARNING,
                message = "Hallucinated",
                rule = "ai-review",
                location = Location.FileRef("src/Fake.kt", 1),
            ),
        )

        AiFindingFilter(defaultConfig, logger).filter(
            findings,
            changedFiles = setOf("src/Real.kt"),
        )

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("[anchor]") })
    }

    @Test
    fun `logs line validation decisions`() {
        val logger = CapturingLogger()
        val findings = listOf(
            Finding(
                severity = Severity.ERROR,
                message = "Bad line",
                rule = "ai-review",
                location = Location.FileRef("src/A.kt", 999),
            ),
        )

        AiFindingFilter(defaultConfig, logger).filter(
            findings,
            diffLineRanges = mapOf("src/A.kt" to setOf(1, 2, 3)),
        )

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("[line-validation]") })
    }
}
