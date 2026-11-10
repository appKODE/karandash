@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.module.PipelineEnvironment
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class AiStatisticsMaxHistoryTest {
    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        sourceBranch = "f",
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = listOf(FileChange("src/Foo.kt", ChangeType.MODIFIED)),
    )

    @Test
    fun `ai statistics maxHistory bounds the recorded runs`() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("karandash-test-maxhistory")
            SystemFileSystem.createDirectories(dir)
            try {
                val config = buildJsonObject { putJsonObject("ai") { putJsonObject("statistics") { put("maxHistory", 2) } } }
                repeat(4) {
                    val extension = AiPipelineExtension(buildTestRegistry())
                    extension.onConfigured(PipelineEnvironment(aiService = null, rawConfig = config, statsDir = dir.toString()))
                    extension.onPostFetch(mr, config)
                    extension.onPipelineComplete()
                }

                assertEquals(2, StatisticsStore(dir.toString()).loadAll().size)
            } finally {
                SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
                SystemFileSystem.delete(dir, mustExist = false)
            }
        }
}
