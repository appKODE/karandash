package io.karandash.plugins.ai.core.config

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.TestFiles
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViolationBaselineTest {
    private fun finding(
        path: String?,
        severity: Severity = Severity.ERROR,
    ) = Finding(severity = severity, message = "m", rule = "r", location = path?.let { Location.FileRef(it, 1) })

    @Test
    fun `files within baseline become INFO and files over budget keep severity`() {
        val findings = listOf(finding("legacy.kt"), finding("legacy.kt"), finding("new.kt"), finding(null))
        val result = ViolationBudgetEvaluator.applyBaseline(
            findings,
            ViolationBaselineConfig(),
            mapOf("legacy.kt" to 2),
            "2026-03-16",
        )
        assertEquals(listOf(Severity.INFO, Severity.INFO, Severity.ERROR, Severity.ERROR), result.findings.map { it.severity })
        assertEquals(mapOf("new.kt" to (1 to 0)), result.overBudget)
        assertEquals(1, result.withinBudgetFiles)
    }

    @Test
    fun `incrementPerMonth raises the allowance per month since baselineDate`() {
        val config = ViolationBaselineConfig(incrementPerMonth = 1, baselineDate = "2026-01-10")
        val result = ViolationBudgetEvaluator.applyBaseline(List(3) { finding("a.kt") }, config, mapOf("a.kt" to 1), "2026-03-16")
        assertTrue(result.findings.all { it.severity == Severity.INFO })
    }

    @Test
    fun `loadBaseline reads a JSON path to count map and rejects bad JSON`() {
        val file = Path(TestFiles.createTestTempDirectory("karandash-baseline"), "baseline.json")
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"a.kt": 3}""") }
        assertEquals(mapOf("a.kt" to 3), ViolationBudgetEvaluator.loadBaseline(file.toString()).getOrThrow())
        SystemFileSystem.sink(file).buffered().use { it.writeString("not json") }
        assertTrue(ViolationBudgetEvaluator.loadBaseline(file.toString()).isFailure)
        SystemFileSystem.delete(file)
    }
}
