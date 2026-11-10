package io.karandash.cli.commands.stats

import com.github.ajalt.clikt.testing.test
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `stats` table, rule filter, limit and clear over a local statistics store. */
class StatsCommandTableTest {
    private val dir = TestFiles.createTestTempDirectory("stats").also {
        SystemFileSystem.createDirectories(it)
    }

    private fun run(vararg args: String) = StatsCommand().test(listOf("--stats-dir", dir.toString()) + args)

    private fun record(
        mrId: String?,
        timestamp: Long,
        byRule: Map<String, Int>,
    ) = StatisticsStore(dir.toString()).record(
        RunRecord(
            timestamp = timestamp,
            mrId = mrId,
            totalFindings = byRule.values.sum(),
            errorCount = 1,
            warningCount = 2,
            infoCount = 0,
            findingsByRule = byRule,
        ),
    )

    @Test
    fun `empty store says nothing is recorded`() {
        assertTrue("No statistics recorded yet" in run().stdout)
    }

    @Test
    fun `table lists runs newest first and totals rules across runs`() {
        record("mr-old", 0L, mapOf("title" to 2))
        record(null, 3_600L, mapOf("title" to 1, "size" to 4))

        val out = run().stdout

        assertTrue("Run History (2 shown)" in out, out)
        assertTrue("1970-01-01 01:00" in out && "1970-01-01 00:00" in out, out)
        assertTrue(out.indexOf("1970-01-01 01:00") < out.indexOf("mr-old"), out)
        val rules = out.substringAfter("Top violations by rule:").lines().filter { it.isNotBlank() }
        assertEquals(listOf("size", "title"), rules.map { it.trim().substringBefore(' ') })
        assertTrue("3 finding(s) across 2 run(s)" in rules[1], rules[1])
    }

    @Test
    fun `rule filter and limit narrow the table`() {
        record("mr-a", 0L, mapOf("title" to 1))
        record("mr-b", 60L, mapOf("size" to 1))
        record("mr-c", 120L, mapOf("size" to 1))

        val filtered = run("--rule", "size", "--limit", "1").stdout

        assertTrue("Run History (1 shown)" in filtered, filtered)
        assertTrue("mr-c" in filtered && "mr-b" !in filtered && "mr-a" !in filtered, filtered)
        assertFalse("title" in filtered, filtered)
    }

    @Test
    fun `rule filter matching nothing prints an empty table and no rules`() {
        record("mr-a", 0L, mapOf("title" to 1))

        val out = run("--rule", "nope").stdout

        assertTrue("Run History (0 shown)" in out, out)
        assertFalse("Top violations" in out, out)
    }

    @Test
    fun `limit below one is a usage error`() {
        record("mr-a", 0L, mapOf("title" to 1))

        val result = run("--limit", "-1")

        assertEquals(1, result.statusCode, result.output)
        assertTrue("--limit" in result.stderr, result.stderr)
    }

    @Test
    fun `clear empties the store`() {
        record("mr-a", 0L, mapOf("title" to 1))

        assertTrue("Statistics cleared" in run("--clear").stdout)
        assertTrue("No statistics recorded yet" in run().stdout)
    }
}
