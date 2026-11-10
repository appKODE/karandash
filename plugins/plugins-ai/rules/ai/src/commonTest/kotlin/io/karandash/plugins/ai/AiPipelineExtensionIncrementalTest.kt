@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.module.ExtensionStateStore
import io.karandash.api.module.PipelineEnvironment
import io.karandash.duration.KarandashDuration
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPipelineExtensionIncrementalTest {
    private class MemoryStore : ExtensionStateStore {
        val entries = mutableMapOf<String, ByteArray>()
        override val ttl: KarandashDuration = KarandashDuration.ofSeconds(60)

        override fun get(key: String): ByteArray? = entries[key]

        override fun put(
            key: String,
            value: ByteArray,
        ) {
            entries[key] = value
        }

        override fun invalidate(key: String) {
            entries.remove(key)
        }

        override fun keys(): List<String> = entries.keys.toList()
    }

    private val store = MemoryStore()

    private fun change(path: String) = FileChange(path, ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = "+$path")

    private val firstPush = createTestMergeRequest(changes = listOf(change("a/A.kt")))
    private val secondPush = createTestMergeRequest(changes = listOf(change("a/A.kt"), change("b/B.kt")))

    private suspend fun runOnce(
        config: String,
        mr: MergeRequest,
    ): AiPipelineExtension {
        val raw = KarandashJson.Default.parseToJsonElement(config)
        val ext = AiPipelineExtension(buildTestRegistry())
        ext.configureStatePersistence(AiStatePersistence(store))
        ext.onConfigured(PipelineEnvironment(aiService = null, rawConfig = raw, statsDir = ".karandash/stats"))
        ext.getCachedFindings(mr, raw.jsonObject.getValue("ai"), NoOpLogger)
        ext.saveIncrementalState(mr, emptyList())
        return ext
    }

    @Test
    fun `a second push reports the unchanged files it skipped`() =
        runTest {
            runOnce("""{"ai": {}}""", firstPush)
            val ext = runOnce("""{"ai": {}}""", secondPush)

            val summary = ext.beforeReporting(emptyList(), secondPush).single { it.rule == IncrementalSummary.RULE }

            assertEquals("1", summary.metadata["skippedFiles"])
            assertEquals("1", summary.metadata["reviewedFiles"])
            assertTrue(summary.explanation.orEmpty().contains("a/A.kt"))
        }

    @Test
    fun `incremental false reviews everything and adds no summary`() =
        runTest {
            runOnce("""{"ai": {"incremental": false}}""", firstPush)
            val ext = runOnce("""{"ai": {"incremental": false}}""", secondPush)

            assertNull(ext.incrementalChangedFiles())
            assertTrue(ext.beforeReporting(emptyList(), secondPush).none { it.rule == IncrementalSummary.RULE })
        }

    @Test
    fun `large-mr incremental false reviews an oversized MR in full`() =
        runTest {
            val config = """{"ai": {"largeMr": {"maxUnits": 1, "incremental": false}}}"""
            runOnce(config, firstPush)
            val ext = runOnce(config, secondPush)

            assertNull(ext.incrementalChangedFiles())
        }

    @Test
    fun `large-mr incremental true keeps an oversized MR incremental when ai incremental is off`() =
        runTest {
            val config = """{"ai": {"incremental": false, "largeMr": {"maxUnits": 1, "incremental": true}}}"""
            // The first push is oversized too, so its snapshot is saved.
            runOnce(config, createTestMergeRequest(changes = listOf(change("a/A.kt"), change("c/C.kt"))))
            val ext = runOnce(config, secondPush)

            assertTrue(ext.beforeReporting(emptyList(), secondPush).any { it.rule == IncrementalSummary.RULE })
        }

    @Test
    fun `large-mr incremental leaves an MR within max-units to ai incremental`() =
        runTest {
            val config = """{"ai": {"largeMr": {"maxUnits": 2, "incremental": false}}}"""
            runOnce(config, firstPush)
            val ext = runOnce(config, secondPush)

            assertTrue(ext.beforeReporting(emptyList(), secondPush).any { it.rule == IncrementalSummary.RULE })
        }

    private fun pushed(
        vararg history: String,
        changes: List<FileChange> = secondPush.changes,
    ) = createTestMergeRequest(
        changes = changes,
        commits = history.map { Commit(it, "m", CommitAuthor("a", "a@x"), "2026-01-01T00:00:00Z") },
    ).copy(diffRefs = DiffRefs(baseSha = "base", headSha = history.last()))

    private val oversized = """{"ai": {"largeMr": {"maxUnits": 1, "incremental": true}}}"""

    @Test
    fun `a force push reviews an oversized MR in full and says why`() =
        runTest {
            runOnce(oversized, pushed("aaaaaaaa1", changes = secondPush.changes))
            val rewritten = pushed("cccccccc1")
            val ext = runOnce(oversized, rewritten)

            assertEquals(setOf("a/A.kt", "b/B.kt"), ext.incrementalChangedFiles())
            val note = ext.beforeReporting(emptyList(), rewritten).single { it.rule == IncrementalSummary.RULE }
            assertEquals(
                "Incremental AI review: all 2 file(s) reviewed, last reviewed commit aaaaaaaa is not in the MR history",
                note.message,
            )
        }

    @Test
    fun `a stored snapshot without a last reviewed commit reviews an oversized MR in full and says why`() =
        runTest {
            runOnce(oversized, secondPush)
            val withHistory = pushed("bbbbbbbb1")
            val ext = runOnce(oversized, withHistory)

            assertEquals(setOf("a/A.kt", "b/B.kt"), ext.incrementalChangedFiles())
            val note = ext.beforeReporting(emptyList(), withHistory).single { it.rule == IncrementalSummary.RULE }
            assertEquals("Incremental AI review: all 2 file(s) reviewed, no last reviewed commit stored", note.message)
        }

    @Test
    fun `a push on top of the last reviewed commit stays incremental`() =
        runTest {
            runOnce(oversized, pushed("aaaaaaaa1", changes = listOf(change("a/A.kt"), change("c/C.kt"))))
            val next = pushed("aaaaaaaa1", "bbbbbbbb1")
            val ext = runOnce(oversized, next)

            assertEquals(setOf("b/B.kt"), ext.incrementalChangedFiles())
            val note = ext.beforeReporting(emptyList(), next).single { it.rule == IncrementalSummary.RULE }
            assertEquals("1", note.metadata["skippedFiles"])
        }

    @Test
    fun `a history long enough to be truncated by the platform stays incremental`() =
        runTest {
            runOnce(oversized, pushed("aaaaaaaa1", changes = listOf(change("a/A.kt"), change("c/C.kt"))))
            val next = pushed(*Array(250) { "dddddddd$it" })
            val ext = runOnce(oversized, next)

            assertEquals(setOf("b/B.kt"), ext.incrementalChangedFiles())
        }

    /** The incremental cache key changes with the tool version (new built-in prompts). */
    @Test
    fun `the incremental hash includes the tool version`() {
        val ai = KarandashJson.Default.parseToJsonElement("""{"provider":"openai"}""")
        val current = AiPipelineExtension.incrementalConfigHash(null, ai)
        assertEquals(current, AiPipelineExtension.incrementalConfigHash(null, ai))
        assertTrue(current != AiPipelineExtension.incrementalConfigHash(null, ai, version = "0.0.1"))
    }
}
