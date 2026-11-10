package io.karandash.contracts.fw

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.commands.report.ReportSummaryCommand
import io.karandash.engine.ReportDataBuilder
import io.karandash.engine.ValidationResult
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.formats.JsonReportFormatModule
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `karandash report summary` cannot read the JSON that `--reporter json` writes.
 * `JsonReportFormatModule` emits each finding's rule as `"rule"`, while `ReportSummaryCommand.printSummary`
 * reads `"ruleId"`, so every finding is counted under `unknown`.
 */
class ReportSummaryContractTest {
    @Test
    fun `report summary groups findings from json reporter output by rule id`() =
        runTest {
            val findings = listOf(
                Finding(severity = Severity.ERROR, message = "Title too short", rule = "title-length"),
                Finding(severity = Severity.WARNING, message = "No ticket", rule = "jira-ticket"),
            )
            val body = JsonReportFormatModule()
                .format(ReportDataBuilder.build(ValidationResult(findings = findings)), JsonObject(emptyMap()))
                .body
            val dir = TestFiles.createTestTempDirectory("report-summary")
            val file = Path(dir, "report.json")
            try {
                TestFiles.writeText(file, body)

                val output = ReportSummaryCommand().test(listOf("--from", file.toString())).output

                assertTrue("title-length" in output, "summary lost rule id 'title-length':\n$output")
                assertTrue("jira-ticket" in output, "summary lost rule id 'jira-ticket':\n$output")
                assertFalse("unknown" in output, "summary bucketed findings under 'unknown':\n$output")
            } finally {
                TestFiles.deleteRecursively(dir)
            }
        }
}
