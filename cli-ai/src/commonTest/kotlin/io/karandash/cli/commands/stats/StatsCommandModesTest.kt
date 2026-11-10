package io.karandash.cli.commands.stats

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.commands.stats.audit.AuditFixtures
import io.karandash.cli.commands.stats.audit.AuditOptions
import io.karandash.cli.commands.stats.audit.StatsAuditRunner
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `stats --json` export and `stats --audit` without `--out`. */
class StatsCommandModesTest {
    @Test
    fun `json exports the whole history`() {
        val dir = TestFiles.createTestTempDirectory("stats-json")
        SystemFileSystem.createDirectories(dir)
        val record =
            RunRecord(
                timestamp = 1,
                mrId = "7",
                totalFindings = 2,
                errorCount = 1,
                warningCount = 1,
                infoCount = 0,
                findingsByRule = mapOf("a" to 2)
            )
        StatisticsStore(dir.toString()).record(record)

        val result = StatsCommand().test(listOf("--stats-dir", "$dir", "--json", "--limit", "1"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf(record), KarandashJson.Default.decodeFromString(ListSerializer(RunRecord.serializer()), result.stdout))
    }

    @Test
    fun `audit without out prints the json on stdout and the tables on stderr`() {
        val dir = AuditFixtures.writeCorpus(AuditFixtures.corpus())

        val result = StatsCommand().test(listOf("--audit", "--corpus", "$dir", "--bot", AuditFixtures.BOT))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(StatsAuditRunner.run(dir, AuditOptions(bots = setOf(AuditFixtures.BOT))).json + "\n", result.stdout)
        assertContains(result.stderr, "precision")
    }
}
