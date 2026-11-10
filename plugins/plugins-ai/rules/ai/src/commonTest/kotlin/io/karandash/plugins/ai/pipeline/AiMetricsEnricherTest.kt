package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiMetricsEnricherTest {
    private val enricher = AiMetricsEnricher(AiPipelineConfig(), NoOpLogger)
    private val warnings = mutableListOf<String>()

    private fun options(json: String): AiMetricsOptions = AiMetricsOptions.from(parse(json)) { warnings += it }

    private fun parse(json: String): JsonElement = KarandashJson.Default.parseToJsonElement(json)

    private fun change(
        path: String,
        lines: Int = 1,
        diff: String? = null,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = lines, diffContent = diff)

    private val base = listOf(Finding(severity = Severity.WARNING, message = "m", rule = "r"))

    @Test
    fun `options read camelCase and kebab-case metric blocks and boolean shorthand`() {
        val parsed = options(
            """{"ai":{"metrics":{"change-entropy":{"includeInMetadata":true},
               "cognitiveLoad":{"estimatedReviewTime":true},
               "hotspotAnalysis":{"churnThreshold":50,"complexityThreshold":10}},
               "cloneDetection":true,"naturalness":{"surpriseThreshold":0.7}}}""",
        )
        assertTrue(parsed.changeEntropy.includeInMetadata)
        assertTrue(parsed.cognitiveLoad.estimatedReviewTime)
        assertEquals(HotspotOptions(churnThreshold = 50, complexityThreshold = 10), parsed.hotspot)
        assertEquals(true, parsed.cloneDetection?.enabled)
        assertEquals(0.7, parsed.naturalness?.surpriseThreshold)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `malformed block warns and keeps the default`() {
        val parsed = options("""{"ai":{"cloneDetection":{"threshold":"high"}}}""")
        assertNull(parsed.cloneDetection)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `includeInMetadata stamps change entropy on every finding`() {
        val mr = createTestMergeRequest(changes = listOf(change("a/A.kt"), change("b/B.kt")))
        val result = enricher.enrich(
            base,
            mr,
            setOf("change-entropy"),
            AiMetricsOptions(changeEntropy = ChangeEntropyOptions(includeInMetadata = true)),
        )
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.metadata.containsKey("changeEntropy") })
    }

    @Test
    fun `estimatedReviewTime reports the time for a low-load MR`() {
        val mr = createTestMergeRequest(changes = listOf(change("A.kt", lines = 5)))
        assertEquals(base, enricher.enrich(base, mr, setOf("cognitive-load")))

        val result = enricher.enrich(
            base,
            mr,
            setOf("cognitive-load"),
            AiMetricsOptions(cognitiveLoad = CognitiveLoadOptions(estimatedReviewTime = true)),
        )
        val load = result.single { it.rule == "metrics:cognitive-load" }
        assertEquals(Severity.INFO, load.severity)
        assertTrue("estimated review time" in load.message)
    }

    @Test
    fun `hotspotAnalysis enables hotspot with its own saturation thresholds`() {
        val mr = createTestMergeRequest(changes = listOf(change("Hot.kt", lines = 20)))
        assertTrue(enricher.enrich(base, mr, setOf("hotspot-analysis")).none { it.rule == "metrics:hotspot" })

        val tight = AiMetricsOptions(hotspot = HotspotOptions(churnThreshold = 10, complexityThreshold = 10))
        val result = enricher.enrich(base, mr, setOf("hotspot-analysis"), tight)
        assertNotNull(result.singleOrNull { it.rule == "metrics:hotspot" })
    }

    @Test
    fun `cloneDetection block enables the metric and onDetection warn raises severity`() {
        val body = (1..10).joinToString("\n") { "+val value$it = compute($it) + offset" }
        val mr = createTestMergeRequest(changes = listOf(change("A.kt", diff = body), change("B.kt", diff = body)))

        val warn = AiMetricsOptions(cloneDetection = CloneDetectionOptions(threshold = 0.5, onDetection = "warn"))
        val clones = enricher.enrich(base, mr, emptySet(), warn).filter { it.rule == "metrics:clone-detection" }
        assertEquals(listOf(Severity.WARNING), clones.map { it.severity })

        val off = AiMetricsOptions(cloneDetection = CloneDetectionOptions(enabled = false))
        assertEquals(base, enricher.enrich(base, mr, emptySet(), off))
    }

    @Test
    fun `naturalness block flags by surpriseThreshold unless flagUnusualPatterns is false`() {
        val diff = "+" + ('c'..'p').joinToString(" ") { "a b $it" }
        val mr = createTestMergeRequest(changes = listOf(change("N.kt", diff = diff)))

        val flag = AiMetricsOptions(naturalness = NaturalnessOptions(surpriseThreshold = 0.0))
        assertNotNull(enricher.enrich(base, mr, emptySet(), flag).singleOrNull { it.rule == "metrics:naturalness" })

        val logOnly = AiMetricsOptions(naturalness = NaturalnessOptions(flagUnusualPatterns = false, surpriseThreshold = 0.0))
        assertEquals(base, enricher.enrich(base, mr, emptySet(), logOnly))
    }
}
