package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffChunkerOverlapTest {
    private fun hunk(n: Int) = "@@ -$n,1 +$n,1 @@\n-old $n\n+new $n " + "x".repeat(HUNK_PADDING)

    private fun bigChange(hunks: Int) =
        FileChange(
            path = "src/Big.kt",
            changeType = ChangeType.MODIFIED,
            additions = hunks,
            deletions = hunks,
            diffContent = (1..hunks).joinToString("\n") { hunk(it) },
        )

    @Test
    fun `a file larger than the chunk size splits on hunk boundaries with overlapping windows`() {
        val change = bigChange(hunks = 6)
        val hunkSize = hunk(1).length
        val chunks = DiffChunker(maxCharsPerChunk = hunkSize * 2 + 2, maxChunks = 10, overlapChars = hunkSize)
            .chunk(createTestMergeRequest(changes = listOf(change)))

        val windows = chunks.flatMap { it.changes }.map { checkNotNull(it.diffContent) }
        assertTrue(windows.size > 1)
        windows.forEach { assertTrue(it.startsWith("@@")) }
        // Each window after the first repeats the previous window's last hunk.
        windows.zipWithNext().forEach { (prev, next) ->
            assertEquals(splitHunks(prev).last(), splitHunks(next).first())
        }
        val covered = windows.flatMap { splitHunks(it) }.toSet()
        assertEquals((1..6).map { hunk(it) }.toSet(), covered)
    }

    @Test
    fun `without an overlap a large file still splits on hunk boundaries`() {
        val change = bigChange(hunks = 6)
        val hunkSize = hunk(1).length
        val chunks = DiffChunker(maxCharsPerChunk = hunkSize * 2 + 2, maxChunks = 10)
            .chunk(createTestMergeRequest(changes = listOf(change)))

        val windows = chunks.flatMap { it.changes }.map { checkNotNull(it.diffContent) }
        assertEquals(3, windows.size)
        assertEquals((1..6).map { hunk(it) }, windows.flatMap { splitHunks(it) })
    }

    @Test
    fun `a file within the chunk size stays one change`() {
        val change = bigChange(hunks = 2)
        val chunks = DiffChunker(maxCharsPerChunk = checkNotNull(change.diffContent).length, maxChunks = 10)
            .chunk(createTestMergeRequest(changes = listOf(change)))
        assertEquals(listOf(change), chunks.flatMap { it.changes })
    }

    @Test
    fun `a single hunk larger than the chunk size stays whole`() {
        val change = bigChange(hunks = 1)
        val chunks = DiffChunker(maxCharsPerChunk = 10, maxChunks = 10, overlapChars = 5)
            .chunk(createTestMergeRequest(changes = listOf(change)))
        assertEquals(listOf(change.diffContent), chunks.flatMap { it.changes }.map { it.diffContent })
    }

    @Test
    fun `hunk windows past maxChunks are reported as dropped hunks`() {
        val change = bigChange(hunks = 8)
        val result = DiffChunker(maxCharsPerChunk = hunk(1).length + 1, maxChunks = 3)
            .split(createTestMergeRequest(changes = listOf(change)))

        assertEquals(3, result.chunks.size)
        assertEquals(listOf(DroppedDiff("src/Big.kt", droppedHunks = 5, totalHunks = 8)), result.dropped)
        assertEquals("src/Big.kt (5 of 8 hunks)", result.dropped.single().describe())
    }

    @Test
    fun `a file that fits the chunk limit reports nothing dropped`() {
        val change = bigChange(hunks = 3)
        val result = DiffChunker(maxCharsPerChunk = hunk(1).length + 1, maxChunks = 3)
            .split(createTestMergeRequest(changes = listOf(change)))

        assertEquals(3, result.chunks.size)
        assertEquals(emptyList(), result.dropped)
    }

    @Test
    fun `a file in no chunk is dropped whole`() {
        val small = FileChange("src/Small.kt", ChangeType.MODIFIED, 1, 1, diffContent = hunk(1))
        val result = DiffChunker(maxCharsPerChunk = hunk(1).length + 1, maxChunks = 1)
            .split(createTestMergeRequest(changes = listOf(small, small.copy(path = "src/Other.kt"))))

        assertEquals("src/Other.kt", result.dropped.single().describe())
    }

    private fun windowsFor(limits: String): List<String> {
        val config = AiRuleConfig.fromPluginConfig(Json.parseToJsonElement("""{"limits": $limits}"""))
        val change = bigChange(hunks = 6)
        return DiffChunker(
            maxCharsPerChunk = hunk(1).length * 2 + 2,
            maxChunks = 10,
            overlapLines = config.chunkOverlapLines,
        ).chunk(createTestMergeRequest(changes = listOf(change)))
            .flatMap { it.changes }
            .map { checkNotNull(it.diffContent) }
    }

    /** `ai.limits.chunkOverlapLines` repeats the trailing hunks that fit in that many lines. */
    @Test
    fun `chunkOverlapLines repeats the previous window last hunk in the next window`() {
        val windows = windowsFor("""{"chunkOverlapLines": 3}""")
        assertTrue(windows.size > 3)
        windows.zipWithNext().forEach { (prev, next) -> assertEquals(splitHunks(prev).last(), splitHunks(next).first()) }
    }

    @Test
    fun `unset chunkOverlapLines carries no hunk`() {
        val windows = windowsFor("{}")
        assertEquals(AiRuleConfig.DEFAULT_CHUNK_OVERLAP_LINES, 0)
        assertEquals((1..6).map { hunk(it) }, windows.flatMap { splitHunks(it) })
    }

    @Test
    fun `splitHunks keeps a preamble with the first hunk`() {
        assertEquals(
            listOf("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a", "@@ -5 +5 @@\n+b"),
            splitHunks("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n@@ -5 +5 @@\n+b"),
        )
    }

    private companion object {
        const val HUNK_PADDING = 40
    }
}
