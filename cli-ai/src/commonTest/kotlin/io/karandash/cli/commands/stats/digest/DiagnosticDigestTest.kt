package io.karandash.cli.commands.stats.digest

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.commands.stats.StatsCommand
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticDigestTest {
    private fun run(
        mr: String,
        at: Long,
        passed: Boolean,
        durationMs: Long,
        findings: List<DigestFinding>,
        rules: List<DigestRule>,
        falsePositives: Int = 0,
    ) = DigestReport(
        events = listOf(DigestStamp(at)),
        summary = DigestSummary(durationMs, DigestCache(hits = 1, misses = 1), DigestFindingSummary(findings.size, passed)),
        context = DigestContext(mrUrl = mr, ruleIds = listOf("titleFormat", "labels", "aiReview")),
        rules = rules,
        findings = findings,
        feedback = DigestFeedback(falsePositives),
    )

    private val title = DigestFinding("titleFormat", "too long")
    private val ai = DigestFinding("aiReview", "null check", "a.kt")
    private val aiOk = DigestRule("aiReview", "ok", model = "m", costUsd = 0.25, findingCount = 1)
    private val aiDown = DigestRule("aiReview", "error", category = "AI_PROVIDER_ERROR", model = "m")
    private val titleRule = DigestRule("titleFormat", "ok", fromCache = true)

    private val reports =
        listOf(
            run(
                "mr/1",
                at = 2,
                passed = true,
                durationMs = 2000,
                findings = listOf(ai),
                rules = listOf(aiOk, titleRule),
                falsePositives = 1
            ),
            run("mr/1", at = 1, passed = false, durationMs = 1000, findings = listOf(title, ai), rules = listOf(aiOk, titleRule)),
            run("mr/2", at = 1, passed = false, durationMs = 9000, findings = listOf(title), rules = listOf(aiDown, titleRule)),
        )

    @Test
    fun `digest counts red runs AI availability fix rate dead rules cost cache duration and false positives`() {
        val d = DiagnosticDigest.compute(reports)
        assertEquals(3, d.runs)
        assertEquals(2, d.mergeRequests)
        assertEquals(2.0 / 3, d.redRunRate)
        assertEquals(3, d.aiRuns)
        assertEquals(2.0 / 3, d.aiAvailability)
        assertEquals(0.5, d.fixRate)
        assertEquals(1, d.fixRateMergeRequests)
        assertEquals(listOf("labels"), d.deadRules)
        assertEquals(0.5, d.aiCostUsd)
        assertEquals(0.25, d.aiCostPerMergeRequest)
        assertEquals(0.5, d.cacheHitRate)
        assertEquals(2000L, d.durationP50Ms)
        assertEquals(9000L, d.durationP95Ms)
        assertEquals(1, d.falsePositives)
    }

    @Test
    fun `wildcard matches star and question mark only`() {
        assertTrue(DiagnosticDigest.wildcard("diag-*.json", "diag-12.json"))
        assertTrue(DiagnosticDigest.wildcard("d?.json", "d1.json"))
        assertEquals(false, DiagnosticDigest.wildcard("diag-*.json", "diag-12.txt"))
    }

    @Test
    fun `stats reads a directory of diagnostic files and prints markdown`() {
        val dir = TestFiles.createTestTempDirectory("digest")
        try {
            SystemFileSystem.createDirectories(Path(dir, "job-1"))
            reports.forEachIndexed { i, r ->
                TestFiles.writeText(Path(dir, "job-1", "diag-$i.json"), KarandashJson.Default.encodeToString(DigestReport.serializer(), r))
            }
            TestFiles.writeText(Path(dir, "broken.json"), "not json")

            val result = StatsCommand().test(listOf("--diagnostics", dir.toString(), "--format", "markdown"))

            assertTrue("| Red runs | 66.7% |" in result.stdout, result.stdout)
            assertTrue("| Dead rules | labels |" in result.stdout, result.stdout)
            assertTrue("broken.json" in result.stderr, result.stderr)
        } finally {
            TestFiles.deleteRecursively(dir)
        }
    }
}
