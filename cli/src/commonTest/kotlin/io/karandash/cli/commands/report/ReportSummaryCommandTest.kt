package io.karandash.cli.commands.report

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ReportSummaryCommandTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("karandash-report-test")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    @Test
    fun `prints summary from valid result file`() {
        val resultJson = """
            {
              "passed": false,
              "findings": [
                {"rule": "title-format", "severity": "error", "message": "Bad title"},
                {"rule": "title-format", "severity": "error", "message": "Bad title 2"},
                {"rule": "description-length", "severity": "warning", "message": "Too short"}
              ]
            }
        """.trimIndent()
        val resultFile = Path(tempDir, "results.json")
        TestFileHelpers.writeFile(resultFile, resultJson)

        val command = ReportSummaryCommand()
        val result = command.test(listOf("--from", resultFile.toString()))

        assertEquals(0, result.statusCode)
        assertContains(result.output, "Total findings: 3")
        assertContains(result.output, "FAILED")
        assertContains(result.output, "error")
        assertContains(result.output, "title-format")
    }

    @Test
    fun `groups by the rule field the json reporter writes`() {
        val resultFile = Path(tempDir, "results.json")
        TestFileHelpers.writeFile(resultFile, """{"findings": [{"rule": "jira-ticket", "severity": "warning", "message": "x"}]}""")

        val output = ReportSummaryCommand().test(listOf("--from", resultFile.toString())).output

        assertContains(output, "jira-ticket")
        assertFalse("unknown" in output, output)
    }

    @Test
    fun `prints passed result`() {
        val resultJson = """
            {
              "passed": true,
              "findings": []
            }
        """.trimIndent()
        val resultFile = Path(tempDir, "results.json")
        TestFileHelpers.writeFile(resultFile, resultJson)

        val command = ReportSummaryCommand()
        val result = command.test(listOf("--from", resultFile.toString()))

        assertEquals(0, result.statusCode)
        assertContains(result.output, "Total findings: 0")
        assertContains(result.output, "PASSED")
    }

    @Test
    fun `reports error for missing file`() {
        val command = ReportSummaryCommand()
        val result = command.test(listOf("--from", "/nonexistent/file.json"))

        assertNotEquals(0, result.statusCode)
    }
}
