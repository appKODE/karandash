package io.karandash.cli.commands.stats

import com.github.ajalt.clikt.testing.test
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** `stats` without `--stats-dir` reads `ai.statistics.dir` from config, where the AI pipeline writes. */
class StatsCommandDirTest {
    @Test
    fun defaultDirComesFromAiStatisticsDir() {
        val dir = TestFiles.createTestTempDirectory("stats")
        SystemFileSystem.createDirectories(dir)
        StatisticsStore(dir.toString()).record(
            RunRecord(
                timestamp = 0L,
                mrId = "mr-from-config-dir",
                totalFindings = 1,
                errorCount = 1,
                warningCount = 0,
                infoCount = 0,
                findingsByRule = mapOf("r" to 1),
            ),
        )
        val ai = JsonObject(mapOf("statistics" to JsonObject(mapOf("dir" to JsonPrimitive(dir.toString())))))

        val result = StatsCommand(aiConfig = { ai }).test(listOf("--json"))

        assertTrue("mr-from-config-dir" in result.stdout, result.stdout)
    }
}
