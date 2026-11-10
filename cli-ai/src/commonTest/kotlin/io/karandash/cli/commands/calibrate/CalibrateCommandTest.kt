package io.karandash.cli.commands.calibrate

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.errors.ExitCodes
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `calibrate` statistics mode edges and corpus failures. */
class CalibrateCommandTest {
    private fun statsDir(runs: Int): String {
        val dir = TestFiles.createTestTempDirectory("calibrate")
        SystemFileSystem.createDirectories(dir)
        val store = StatisticsStore(dir.toString())
        repeat(runs) { i ->
            store.record(
                RunRecord(
                    timestamp = i.toLong(),
                    mrId = null,
                    totalFindings = 1,
                    errorCount = 0,
                    warningCount = 1,
                    infoCount = 0,
                    findingsByRule = mapOf("a" to 1)
                )
            )
        }
        return dir.toString()
    }

    @Test
    fun `fewer runs than min samples is insufficient data`() {
        val result = CalibrateCommand().test(listOf("--stats-dir", statsDir(3)))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "Insufficient data: 3 runs found, need at least 20")
    }

    @Test
    fun `min samples lowers the bar`() {
        val result = CalibrateCommand().test(listOf("--stats-dir", statsDir(3), "--min-samples", "2"))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "All rules are well-calibrated (3 runs analyzed)")
    }

    @Test
    fun `a corpus that does not exist fails with the config exit code`() {
        val result = CalibrateCommand().test(listOf("--corpus", "/nonexistent/karandash-corpus"))
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertContains(result.stderr, "Calibration failed:")
    }
}
