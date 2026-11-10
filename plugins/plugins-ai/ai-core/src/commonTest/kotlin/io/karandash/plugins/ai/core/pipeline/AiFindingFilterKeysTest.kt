package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `ai.filter` keys beyond caps and confidence: line validation, anchoring, metadata and dedup key. */
class AiFindingFilterKeysTest {
    private fun ai(
        path: String,
        line: Int?,
        message: String = "issue",
        severity: Severity = Severity.ERROR,
        metadata: Map<String, String> = emptyMap(),
    ) = Finding(severity = severity, message = message, rule = "ai-review", location = Location.FileRef(path, line), metadata = metadata)

    private val lines = mapOf("A.kt" to setOf(1, 2, 3))

    @Test
    fun `keys decode from config json`() {
        val raw = """{"attemptJsonRecovery":false,"validateDiffLines":false,"downgradeInvalidLines":true,""" +
            """"anchored":false,"requireFileInDiff":false,"confidenceMetadata":false,"deduplicateKey":"rule:file"}"""
        val cfg = KarandashJson.Default.decodeFromJsonElement(FilterConfig.serializer(), Json.parseToJsonElement(raw) as JsonObject)
        assertEquals(
            FilterConfig(
                attemptJsonRecovery = false,
                validateDiffLines = false,
                downgradeInvalidLines = true,
                anchored = false,
                requireFileInDiff = false,
                confidenceMetadata = false,
                deduplicateKey = "rule:file",
            ),
            cfg,
        )
    }

    @Test
    fun `line outside diff is dropped by default`() {
        val result = AiFindingFilter(FilterConfig()).filter(listOf(ai("A.kt", 9)), diffLineRanges = lines)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `validateDiffLines false keeps line outside diff`() {
        val result = AiFindingFilter(FilterConfig(validateDiffLines = false)).filter(listOf(ai("A.kt", 9)), diffLineRanges = lines)
        assertEquals(Severity.ERROR, result.single().severity)
    }

    @Test
    fun `downgradeInvalidLines turns line outside diff into info`() {
        val result = AiFindingFilter(FilterConfig(downgradeInvalidLines = true))
            .filter(listOf(ai("A.kt", 9), ai("A.kt", 2, "kept")), diffLineRanges = lines)
        val downgraded = result.single { it.location == Location.FileRef("A.kt", 9) }
        assertEquals(Severity.INFO, downgraded.severity)
        assertTrue(downgraded.message.startsWith("[line 9 not in diff]"))
        assertEquals(Severity.ERROR, result.single { it.message == "kept" }.severity)
    }

    @Test
    fun `file outside diff is dropped by default`() {
        val result = AiFindingFilter(FilterConfig()).filter(listOf(ai("B.kt", 1)), changedFiles = setOf("A.kt"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `anchored false keeps file outside diff`() {
        val result = AiFindingFilter(FilterConfig(anchored = false)).filter(listOf(ai("B.kt", 1)), changedFiles = setOf("A.kt"))
        assertEquals(Severity.ERROR, result.single().severity)
    }

    @Test
    fun `requireFileInDiff false downgrades file outside diff`() {
        val result = AiFindingFilter(FilterConfig(requireFileInDiff = false)).filter(listOf(ai("B.kt", 1)), changedFiles = setOf("A.kt"))
        assertEquals(Severity.INFO, result.single().severity)
        assertTrue(result.single().message.startsWith("[B.kt not in diff]"))
    }

    @Test
    fun `confidenceMetadata false strips the score after filtering`() {
        val meta = mapOf(AiFindingMetadataKeys.CONFIDENCE to "0.9")
        val kept = AiFindingFilter(FilterConfig()).filter(listOf(ai("A.kt", 1, metadata = meta)))
        assertEquals("0.9", kept.single().metadata[AiFindingMetadataKeys.CONFIDENCE])
        val stripped = AiFindingFilter(FilterConfig(confidenceMetadata = false))
            .filter(
                listOf(ai("A.kt", 1, metadata = meta), ai("A.kt", 2, "low", metadata = mapOf(AiFindingMetadataKeys.CONFIDENCE to "0.1")))
            )
        assertFalse(AiFindingMetadataKeys.CONFIDENCE in stripped.single().metadata)
    }

    @Test
    fun `deduplicateKey rule file line merges different messages on one line`() {
        val findings = listOf(ai("A.kt", 1, "first", Severity.INFO), ai("A.kt", 1, "second"), ai("A.kt", 2, "third"))
        assertEquals(3, AiFindingFilter(FilterConfig()).filter(findings).size)
        val result = AiFindingFilter(FilterConfig(deduplicateKey = FilterConfig.DEDUP_KEY_RULE_FILE_LINE)).filter(findings)
        assertEquals(listOf("second", "third"), result.map { it.message }.sorted())
    }

    @Test
    fun `deduplicateKey rule file keeps one finding per file`() {
        val findings = listOf(ai("A.kt", 1, "first", Severity.INFO), ai("A.kt", 2, "second"), ai("B.kt", 1, "third"))
        val result = AiFindingFilter(FilterConfig(deduplicateKey = FilterConfig.DEDUP_KEY_RULE_FILE)).filter(findings)
        assertEquals(listOf("second", "third"), result.map { it.message }.sorted())
    }
}
