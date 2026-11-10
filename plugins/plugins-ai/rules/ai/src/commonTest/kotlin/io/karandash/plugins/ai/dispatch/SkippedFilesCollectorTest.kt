package io.karandash.plugins.ai.dispatch

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.IncrementalSummary
import io.karandash.plugins.ai.core.incremental.IncrementalContext
import io.karandash.plugins.ai.core.rules.AiPartialReviewPolicy
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.state.snapshot.SnapshotManager
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkippedFilesCollectorTest {
    private fun change(
        path: String,
        lines: Int = 1,
        diff: String = "+x",
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = lines, diffContent = diff)

    @Test
    fun `preprocessing records too-large filtered and binary files across rules`() {
        val preprocessor = AiMrPreprocessor(NoOpLogger)
        val mr = createTestMergeRequest(
            changes = listOf(
                change("src/A.kt", lines = 30),
                change("src/B.kt", lines = 20),
                change("src/C.kt", lines = 1),
                change("src/generated/Gen.kt"),
                change("img.png", lines = 0, diff = "Binary files a/img.png and b/img.png differ"),
            ),
        )
        val config = AiRuleConfig(partialReviewPolicy = AiPartialReviewPolicy(maxFiles = 2))
        val snapshots = SnapshotManager(persistence = null, mrKey = "mr", logger = NoOpLogger)
        preprocessor.preprocessMrForRule("rule-a", mr, config, snapshots)
        preprocessor.preprocessMrForRule("rule-b", mr, config, snapshots)

        assertEquals(
            SkippedFiles(tooLarge = setOf("src/C.kt"), filtered = setOf("src/generated/Gen.kt"), binary = setOf("img.png")),
            preprocessor.skippedFiles.snapshot(),
        )
    }

    @Test
    fun `incremental summary appends the not-sent counts and metadata`() {
        val context = IncrementalContext(
            mrKey = "mr",
            deltaMr = createTestMergeRequest(),
            unchangedFiles = listOf("U.kt"),
            cachedFindings = emptyList(),
            hasCacheEntry = true,
            currentSnapshot = emptyMap(),
        )
        val plain = IncrementalSummary.finding(context)
        assertTrue(plain?.message?.endsWith("(cached findings reused)") == true)

        val finding = checkNotNull(IncrementalSummary.finding(context, SkippedFiles(tooLarge = setOf("a", "b"), binary = setOf("c"))))
        assertTrue(finding.message.endsWith("; not sent to AI by at least one rule: 2 too large, 1 binary"), finding.message)
        assertEquals("2", finding.metadata["tooLargeFiles"])
        assertEquals("0", finding.metadata["filteredFiles"])
        assertEquals("1", finding.metadata["binaryFiles"])
    }
}
