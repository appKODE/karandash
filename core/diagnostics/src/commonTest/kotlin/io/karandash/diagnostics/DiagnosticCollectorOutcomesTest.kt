package io.karandash.diagnostics

import io.karandash.api.finding.FindingFeedback
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DiagnosticCollectorOutcomesTest {
    private val path = Path(TestFiles.createTestTempDirectory("diag-outcomes"), "diag-outcomes.json")

    @AfterTest
    fun cleanup() {
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
    }

    private fun readReport(): DiagnosticReport =
        KarandashJson.Default.decodeFromString(
            DiagnosticReport.serializer(),
            SystemFileSystem.source(path).buffered().use { it.readString() },
        )

    @Test
    fun `final report carries cache source feedback rule outcomes findings and client lifecycle`() =
        runTest {
            val collector = DiagnosticCollector(path.toString())
            val rules = listOf(
                RuleOutcomeDiagnostic("titleFormat", "ok", findingCount = 1),
                RuleOutcomeDiagnostic("reviewComments", "skipped", reason = "no token", category = "config"),
            )
            val findings = listOf(
                FindingDiagnostic("titleFormat", "error", "bad title"),
                FindingDiagnostic("reviewComments", "warning", "leak", file = "a.kt", line = 3),
            )
            val feedback = FeedbackDiagnostic.of(
                listOf(
                    FindingFeedback("titleFormat", "f1", author = "u"),
                    FindingFeedback("titleFormat", "f2", reaction = "agree", author = "u"),
                ),
            )
            collector.recordCacheSource("remote", firstRun = true)
            collector.recordFeedback(feedback)
            collector.recordOutcomes(rules, findings)
            collector.recordClientLifecycle(creates = 2, closes = 1)
            collector.recordClientClose()

            collector.stopAndFinalize(totalDurationMs = 10)

            val report = readReport()
            val summary = assertNotNull(report.summary)
            assertEquals("remote", summary.cache.source)
            assertEquals(true, summary.cache.firstRun)
            assertEquals(ClientLifecycleDiagnostic(clientCreates = 2, clientCloses = 2), summary.clientLifecycle)
            assertEquals(rules, report.rules)
            assertEquals(findings, report.findings)
            assertEquals(1, report.feedback?.falsePositives)
            assertEquals(mapOf("titleFormat" to 1), report.feedback?.byRule)
        }
}
