@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.module.PipelineEnvironment
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/** The AI extension records nothing for a config without an `ai:` block. */
class AiPipelineExtensionWithoutAiTest {
    private val mr = createTestMergeRequest(changes = listOf(FileChange("src/Foo.kt", ChangeType.MODIFIED, additions = 5)))

    private suspend fun recordedRuns(config: String): Int {
        val dir = TestFiles.createTestTempDirectory("karandash-brk41")
        SystemFileSystem.createDirectories(dir)
        try {
            val element = KarandashJson.Default.parseToJsonElement(config)
            val extension = AiPipelineExtension(buildTestRegistry())
            extension.onConfigured(PipelineEnvironment(aiService = null, rawConfig = element, statsDir = dir.toString()))
            extension.onPostFetch(mr, element)
            extension.afterValidation(emptyList(), mr, element)
            extension.onPipelineComplete()
            return StatisticsStore(dir.toString()).loadAll().size
        } finally {
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(dir, mustExist = false)
        }
    }

    @Test
    fun `a config without an ai block records no AI statistics`() =
        runTest {
            assertEquals(0, recordedRuns("""{"rules":{"title-format":{}}}"""))
        }

    @Test
    fun `a config with an ai block still records statistics`() =
        runTest {
            assertEquals(1, recordedRuns("""{"rules":{"title-format":{}},"ai":{}}"""))
        }
}
