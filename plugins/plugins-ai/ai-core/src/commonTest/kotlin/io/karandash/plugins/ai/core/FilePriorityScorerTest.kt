package io.karandash.plugins.ai.core

import io.karandash.api.context.EnrichmentContext
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.rules.TruncationPriorityConfig
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilePriorityScorerTest {
    private fun change(
        path: String,
        added: Int = 0,
        deleted: Int = 0
    ): FileChange =
        FileChange(
            path = path,
            changeType = ChangeType.MODIFIED,
            additions = added,
            deletions = deleted,
            diffContent = "@@ -1 +1 @@\n+stub",
        )

    private fun enrichmentWithFreq(map: Map<String, Int>): EnrichmentContext {
        val element: JsonElement = buildJsonObject {
            putJsonObject("frequencies") { map.forEach { (k, v) -> put(k, v) } }
        }
        return EnrichmentContext(data = mapOf(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY to element))
    }

    @Test
    fun `empty input returns empty output`() {
        val result = FilePriorityScorer.score(emptyList(), TruncationPriorityConfig())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `kotlin file outscores lock file at equal volume`() {
        val changes = listOf(
            change("src/Auth.kt", added = 10, deleted = 5),
            change("yarn.lock", added = 10, deleted = 5),
        )
        val scored = FilePriorityScorer.score(changes, TruncationPriorityConfig())
        assertEquals("src/Auth.kt", scored.first().change.path)
        assertEquals("yarn.lock", scored.last().change.path)
    }

    @Test
    fun `change volume dominates when weights favour it`() {
        val bigChange = change("src/Tiny.md", added = 200, deleted = 100)
        val smallChange = change("src/Big.kt", added = 2, deleted = 0)
        val config = TruncationPriorityConfig(
            changeVolumeWeight = 0.9,
            fileTypeWeight = 0.05,
            hotspotWeight = 0.05,
            pathPatternWeight = 0.0,
        )
        val scored = FilePriorityScorer.score(listOf(bigChange, smallChange), config)
        assertEquals("src/Tiny.md", scored.first().change.path)
    }

    @Test
    fun `hotspot enrichment bumps frequently-changed files`() {
        val a = change("src/A.kt", added = 5, deleted = 0)
        val b = change("src/B.kt", added = 5, deleted = 0)
        val enrichment = enrichmentWithFreq(mapOf("src/A.kt" to 50, "src/B.kt" to 1))
        val scored = FilePriorityScorer.score(listOf(a, b), TruncationPriorityConfig(), enrichment)
        assertEquals("src/A.kt", scored.first().change.path)
        assertTrue(scored.first().breakdown.hotspot > scored.last().breakdown.hotspot)
    }

    @Test
    fun `hotspot contributes zero when enrichment missing`() {
        val scored = FilePriorityScorer.score(
            listOf(change("src/A.kt", added = 5, deleted = 0)),
            TruncationPriorityConfig(),
            enrichment = null,
        )
        assertEquals(0.0, scored.single().breakdown.hotspot)
    }

    @Test
    fun `malformed enrichment is ignored and hotspot stays zero`() {
        val element: JsonElement = buildJsonObject { put("frequencies", "not-an-object") }
        val enrichment = EnrichmentContext(
            data = mapOf(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY to element),
        )
        val scored = FilePriorityScorer.score(
            listOf(change("src/A.kt", added = 5, deleted = 0)),
            TruncationPriorityConfig(),
            enrichment,
        )
        assertEquals(0.0, scored.single().breakdown.hotspot)
    }

    @Test
    fun `single file with zero change still scores via file type`() {
        val scored = FilePriorityScorer.score(
            listOf(change("Readme.md", added = 0, deleted = 0)),
            TruncationPriorityConfig(),
        )
        // Volume contribution is 0 (no additions); file type is LOW (0.1 * 0.3).
        val breakdown = scored.single().breakdown
        assertEquals(0.0, breakdown.changeVolume)
        assertTrue(breakdown.fileType > 0.0)
    }

    @Test
    fun `score never exceeds 1_0`() {
        val scored = FilePriorityScorer.score(
            listOf(change("src/A.kt", added = 10_000, deleted = 10_000)),
            TruncationPriorityConfig(),
            enrichmentWithFreq(mapOf("src/A.kt" to 1_000_000)),
        )
        assertTrue(scored.single().score <= 1.0)
    }

    @Test
    fun `output is sorted descending by score`() {
        val changes = listOf(
            change("src/Low.md", added = 1, deleted = 0),
            change("src/High.kt", added = 100, deleted = 50),
            change("src/Mid.yaml", added = 10, deleted = 5),
        )
        val scored = FilePriorityScorer.score(changes, TruncationPriorityConfig())
        for (i in 0 until scored.size - 1) {
            assertTrue(scored[i].score >= scored[i + 1].score, "Not sorted at index $i")
        }
    }
}
