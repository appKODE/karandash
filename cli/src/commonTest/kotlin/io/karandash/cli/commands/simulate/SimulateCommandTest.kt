package io.karandash.cli.commands.simulate

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SimulateCommandTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-simulate")

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    private fun config(content: String): String {
        val path = Path(tempDir, "config.yaml")
        TestFileHelpers.writeFile(path, content.trimIndent())
        return path.toString()
    }

    private val twoApprovals = """
        rules:
          approval:
            minApprovals: 2
    """

    @Test
    fun `simulate blocks when the policy is not met`() {
        val result = SimulateCommand().test(listOf("--file", config(twoApprovals), "--approvers", "alice"))

        assertEquals(1, result.statusCode, result.output)
        assertContains(result.output, "Approval Policy Simulation")
        assertContains(result.output, "Hypothetical approvers: alice")
        assertContains(result.output, "Result: blocked")
    }

    @Test
    fun `simulate passes when hypothetical approvers satisfy the policy`() {
        val result = SimulateCommand().test(listOf("--file", config(twoApprovals), "--approvers", "alice,bob"))

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "Result: approval policy satisfied")
    }

    @Test
    fun `simulate without an approval rule has nothing to simulate`() {
        val path = config(
            """
            rules:
              titleFormat:
                pattern: "^feat: .+"
            """,
        )
        val result = SimulateCommand().test(listOf("--file", path))

        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "No 'rules.approval' block")
    }
}
