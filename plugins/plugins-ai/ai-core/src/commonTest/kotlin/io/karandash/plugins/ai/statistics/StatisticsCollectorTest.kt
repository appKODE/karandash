package io.karandash.plugins.ai.statistics

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class StatisticsCollectorTest {
    private fun tempDir(): String {
        val dir = TestFiles.createTestTempDirectory("karandash-test-collector")
        SystemFileSystem.createDirectories(dir)
        return dir.toString()
    }

    private fun finding(
        rule: String,
        severity: Severity
    ) = Finding(
        severity = severity,
        message = "test finding",
        rule = rule,
        location = Location.FileRef(path = "src/Foo.kt"),
    )

    @Test
    fun `collect with empty findings records zero counts`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            StatisticsCollector(store).collect("mr-1", emptyList(), platform = "gitlab", author = "alice")
            val rec = store.loadAll().first()
            assertEquals("gitlab", rec.platform)
            assertEquals("alice", rec.author)
            assertEquals(0, rec.totalFindings)
            assertEquals(0, rec.errorCount)
            assertEquals(0, rec.warningCount)
            assertEquals(0, rec.infoCount)
            assertTrue(rec.findingsByRule.isEmpty())
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `collect groups findings by rule correctly`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            val findings = listOf(
                finding("title-format", Severity.ERROR),
                finding("title-format", Severity.WARNING),
                finding("ai-review", Severity.INFO),
            )
            StatisticsCollector(store).collect("mr-2", findings)
            val rec = store.loadAll().first()
            assertEquals(2, rec.findingsByRule["title-format"])
            assertEquals(1, rec.findingsByRule["ai-review"])
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `collect counts severities correctly`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            val findings = listOf(
                finding("rule-a", Severity.ERROR),
                finding("rule-b", Severity.ERROR),
                finding("rule-c", Severity.WARNING),
                finding("rule-d", Severity.INFO),
            )
            StatisticsCollector(store).collect("mr-3", findings)
            val rec = store.loadAll().first()
            assertEquals(4, rec.totalFindings)
            assertEquals(2, rec.errorCount)
            assertEquals(1, rec.warningCount)
            assertEquals(1, rec.infoCount)
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `collect stores mrId`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            StatisticsCollector(store).collect("mr-99", emptyList())
            assertEquals("mr-99", store.loadAll().first().mrId)
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }

    @Test
    fun `collect stores positive timestamp`() {
        val dir = tempDir()
        try {
            val store = StatisticsStore(dir)
            StatisticsCollector(store).collect(null, emptyList())
            assertTrue(store.loadAll().first().timestamp > 0)
        } finally {
            TestFiles.deleteRecursively(Path(dir))
        }
    }
}
