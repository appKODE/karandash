@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import io.karandash.api.module.PipelineEnvironment
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class AiCalibrationKeyBehaviourTest {
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
    fun `ai calibration enabled logs the rule calibration report`() {
        assertEquals(1, calibrationReports(enabled = true))
        assertEquals(0, calibrationReports(enabled = false))
    }

    private fun calibrationReports(enabled: Boolean): Int {
        val infos = mutableListOf<String>()
        runTest {
            val dir = TestFiles.createTestTempDirectory("karandash-test-calibration")
            SystemFileSystem.createDirectories(dir)
            try {
                val now = Clock.System.now().epochSeconds
                val seed = StatisticsStore(dir.toString())
                repeat(SEEDED_RUNS) { seed.record(RunRecord(now, "m$it", 1, 1, 0, 0, mapOf("rule-a" to 1))) }
                val config = buildJsonObject {
                    putJsonObject("ai") { putJsonObject("calibration") { put("enabled", enabled) } }
                }
                val extension = AiPipelineExtension(buildTestRegistry())
                extension.initialize(context(infos))
                extension.onConfigured(PipelineEnvironment(aiService = null, rawConfig = config, statsDir = dir.toString()))
                extension.onPostFetch(mr, config)
                extension.onPipelineComplete()
            } finally {
                SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
                SystemFileSystem.delete(dir, mustExist = false)
            }
        }
        return infos.count { it.contains("Rule calibration report") }
    }

    private fun context(infos: MutableList<String>): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(emptyMap())
            override val logger: Logger = object : Logger {
                override fun debug(message: String) = Unit

                override fun info(message: String) {
                    infos += message
                }

                override fun warn(message: String) = Unit

                override fun error(message: String) = Unit
            }

            override fun env(name: String): String? = null

            override fun log(message: String) = Unit

            override fun warn(message: String) = Unit

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private companion object {
        const val SEEDED_RUNS = 25
    }
}
