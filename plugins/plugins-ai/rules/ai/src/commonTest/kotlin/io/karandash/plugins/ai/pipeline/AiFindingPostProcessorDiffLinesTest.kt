package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AiFindingPostProcessorDiffLinesTest {
    private val processor = AiFindingPostProcessor(NoOpLogger)

    private val noNewlineHunk = "@@ -1,2 +1,3 @@\n a\n-b\n\\ No newline at end of file\n+b\n+c"

    private fun aiFinding(line: Int) =
        Finding(
            severity = Severity.WARNING,
            message = "Null dereference on line $line",
            rule = "ai-review",
            location = Location.FileRef("A.kt", line),
            metadata = mapOf(AiFindingMetadataKeys.AI_MODEL to "m", AiFindingMetadataKeys.CONFIDENCE to "0.9"),
        )

    @Test
    fun `no newline marker is not counted as a new file line`() {
        assertEquals(setOf(1, 2, 3), AiFindingPostProcessor.extractDiffLineNumbers(noNewlineHunk))
    }

    @Test
    fun `context added and removed lines map to new file numbers across hunks`() {
        val diff = "--- a/A.kt\n+++ b/A.kt\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n@@ -10 +20,2 @@\n+x\n y"

        assertEquals(setOf(1, 2, 3, 20, 21), AiFindingPostProcessor.extractDiffLineNumbers(diff))
        assertEquals(emptySet(), AiFindingPostProcessor.extractDiffLineNumbers(null))
        assertEquals(emptySet(), AiFindingPostProcessor.extractDiffLineNumbers("+orphan line before any hunk"))
    }

    @Test
    fun `filter drops a finding on a phantom line after a no newline marker and keeps a real line`() =
        runTest {
            val mr = createTestMergeRequest(
                changes = listOf(FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, diffContent = noNewlineHunk)),
            )
            val config = Json.parseToJsonElement("""{"ai": {"filter": {}}}""")

            val kept = processor.applyFilter(config, listOf(aiFinding(3), aiFinding(4)), mr)

            assertEquals(listOf(3), kept.map { (it.location as Location.FileRef).line })
        }
}
