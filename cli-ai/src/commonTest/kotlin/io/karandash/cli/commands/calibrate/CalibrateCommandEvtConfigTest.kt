package io.karandash.cli.commands.calibrate

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

/** `calibrate` feeds `ai.research.evtAutoThreshold` into the calibration engine only when it is set. */
class CalibrateCommandEvtConfigTest {
    private fun statsDirWithFiringRule(): String {
        val dir = TestFiles.createTestTempDirectory("calibrate")
        SystemFileSystem.createDirectories(dir)
        val store = StatisticsStore(dir.toString())
        repeat(RUNS) { i ->
            store.record(
                RunRecord(
                    timestamp = i.toLong(),
                    mrId = "mr-$i",
                    totalFindings = 1,
                    errorCount = 1,
                    warningCount = 0,
                    infoCount = 0,
                    findingsByRule = mapOf("noisy-rule" to 1),
                ),
            )
        }
        return dir.toString()
    }

    @Test
    fun unsetEvtConfigKeepsLegacyMinimum() {
        val result = CalibrateCommand().test(listOf("--stats-dir", statsDirWithFiringRule()))

        assertTrue("Rule: noisy-rule" in result.stdout, result.stdout)
    }

    @Test
    fun configuredEvtMinSamplesGatesTheEngine() {
        val ai = JsonObject(
            mapOf(
                "research" to JsonObject(
                    mapOf("evtAutoThreshold" to JsonObject(mapOf("minSamples" to JsonPrimitive(RUNS + 1)))),
                ),
            ),
        )

        val result = CalibrateCommand(aiConfig = { ai }).test(listOf("--stats-dir", statsDirWithFiringRule()))

        assertTrue("All rules are well-calibrated" in result.stdout, result.stdout)
    }

    private companion object {
        const val RUNS = 20
    }
}
