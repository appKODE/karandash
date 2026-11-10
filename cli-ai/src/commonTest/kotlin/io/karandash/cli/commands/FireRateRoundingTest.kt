package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.commands.calibrate.CalibrateCommand
import io.karandash.cli.commands.testrule.TestRuleCommand
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** 58 of 100 runs fire: 0.58 * 100 is 57.99999999999999 in IEEE 754, so a truncating print shows 57%. */
class FireRateRoundingTest {
    private val statsDir: String =
        TestFiles
            .createTestTempDirectory("fire-rate")
            .also { dir ->
                SystemFileSystem.createDirectories(dir)
                val store = StatisticsStore(dir.toString())
                repeat(RUNS) { i ->
                    val fires = if (i < FIRES) 1 else 0
                    store.record(
                        RunRecord(
                            timestamp = i.toLong(),
                            mrId = null,
                            totalFindings = fires,
                            errorCount = 0,
                            warningCount = fires,
                            infoCount = 0,
                            findingsByRule = mapOf("a" to fires),
                        ),
                    )
                }
            }.toString()

    @Test
    fun `calibrate rounds the fire rate`() {
        val result = CalibrateCommand().test(listOf("--stats-dir", statsDir, "--rule", "a", "--target-fp-rate", "0.5"))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "Fire rate: 58%")
    }

    @Test
    fun `test-rule rounds the fire rate`() {
        val result = TestRuleCommand(FakeRules()).test(listOf("a", "--stats-dir", statsDir, "--replay-count", "$RUNS"))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "Fire rate: 58%")
        assertContains(result.stdout, "Fire rate of 58% is within normal range")
    }

    private companion object {
        const val RUNS = 100
        const val FIRES = 58
    }
}
