package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.commands.calibrate.CalibrateCommand
import io.karandash.cli.commands.testrule.TestRuleCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** `calibrate --rule/--window/--target-fp-rate` and `test-rule --against-history/--window` read the recorded history. */
class HistoryFlagsTest {
    private val now = Clock.System.now()

    /** 20 recent runs (rule `a` fires in 6, rule `b` in all) and 20 runs from 200 days ago where `a` always fires. */
    private val statsDir: String =
        TestFiles
            .createTestTempDirectory("history-flags")
            .also { dir ->
                SystemFileSystem.createDirectories(dir)
                val store = StatisticsStore(dir.toString())
                repeat(RUNS) { store.record(run((now - OLD_DAYS.days).epochSeconds, mapOf("a" to 1))) }
                repeat(RUNS) { i ->
                    store.record(run(now.epochSeconds, mapOf("a" to if (i < RECENT_A_FIRES) 1 else 0, "b" to 1)))
                }
            }.toString()

    private fun run(
        timestamp: Long,
        byRule: Map<String, Int>,
    ) = RunRecord(
        timestamp = timestamp,
        mrId = null,
        totalFindings = byRule.values.sum(),
        errorCount = 0,
        warningCount = byRule.values.sum(),
        infoCount = 0,
        findingsByRule = byRule,
    )

    private fun calibrate(vararg args: String) = CalibrateCommand().test(listOf("--stats-dir", statsDir) + args)

    @Test
    fun `calibrate window and rule narrow the analysis`() {
        val result = calibrate("--window", "90", "--rule", "a", "--target-fp-rate", "0.2")
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "Window: last 90 days")
        assertContains(result.stdout, "Calibration Analysis (20 runs)")
        assertContains(result.stdout, "Rule: a")
        assertContains(result.stdout, "Fire rate: 30%")
        assertContains(result.stdout, "Recommendation: LOOSEN")
        assertFalse("Rule: b" in result.stdout, result.stdout)
    }

    @Test
    fun `calibrate without a target keeps the default fire-rate ceiling`() {
        val result = calibrate("--window", "90", "--rule", "a")
        assertContains(result.stdout, "All rules are well-calibrated (20 runs analyzed)")
    }

    @Test
    fun `calibrate without a window analyzes every run`() {
        val result = calibrate("--rule", "a", "--target-fp-rate", "0.5")
        assertContains(result.stdout, "Calibration Analysis (40 runs)")
        assertContains(result.stdout, "Fire rate: 65%")
    }

    @Test
    fun `calibrate rejects a target fire rate outside 0-1`() {
        assertEquals(1, calibrate("--target-fp-rate", "1.5").statusCode)
    }

    private fun testRule(vararg args: String) = TestRuleCommand(UnusedRules).test(listOf("a", "--stats-dir", statsDir) + args)

    @Test
    fun `test-rule against-history with a window replays only recent runs`() {
        val result = testRule("--against-history", "--window", "30")
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "Replayed: 20 MR(s)")
        assertContains(result.stdout, "Fire rate: 30%")
    }

    @Test
    fun `test-rule takes replay count and metrics from ai sandbox`() {
        val sandbox = KarandashJson.Default.parseToJsonElement("""{"sandbox":{"historyWindow":5,"reportMetrics":true}}""")
        val result = TestRuleCommand(UnusedRules) { sandbox }.test(listOf("a", "--stats-dir", statsDir))
        assertContains(result.stdout, "Replayed: 5 MR(s)")
        assertContains(result.stdout, "Affected MRs:")
        val explicit = TestRuleCommand(UnusedRules) { sandbox }.test(listOf("a", "--stats-dir", statsDir, "--replay-count", "3"))
        assertContains(explicit.stdout, "Replayed: 3 MR(s)")
    }

    @Test
    fun `calibrate and test-rule read the history from ai statistics dir without stats-dir`() {
        val ai = KarandashJson.Default.parseToJsonElement("""{"statistics":{"dir":"$statsDir"}}""")
        assertContains(TestRuleCommand(UnusedRules) { ai }.test(listOf("a", "--against-history")).stdout, "Replayed: 40 MR(s)")
        assertFalse("Insufficient data" in CalibrateCommand { ai }.test(listOf("--min-samples", "3")).stdout)
    }

    @Test
    fun `test-rule against-history without a window replays all runs`() {
        val result = testRule("--against-history")
        assertContains(result.stdout, "Replayed: 40 MR(s)")
    }

    @Test
    fun `test-rule window with no recent runs reports empty history`() {
        val empty = TestFiles.createTestTempDirectory("history-empty").toString()
        val result = TestRuleCommand(UnusedRules).test(listOf("a", "--stats-dir", empty, "--against-history", "--window", "7"))
        assertContains(result.stdout, "No validation history found in $empty for the last 7 days")
    }

    @Test
    fun `test-rule against-history conflicts with snapshots`() {
        val result = testRule("--against-history", "--snapshot", "mr.json")
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
        assertContains(result.stderr, "--against-history cannot be combined")
    }

    private object UnusedRules : ConfiguredRulesSource {
        override suspend fun <T> withRules(
            configPath: String?,
            ruleFilter: (String) -> Boolean,
            block: suspend (ConfiguredRules) -> T,
        ): T = error("history mode must not load rules")
    }

    private companion object {
        const val RUNS = 20
        const val RECENT_A_FIRES = 6
        const val OLD_DAYS = 200
    }
}
