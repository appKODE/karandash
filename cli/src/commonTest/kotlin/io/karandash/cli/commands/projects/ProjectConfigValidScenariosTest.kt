package io.karandash.cli.commands.projects

import io.karandash.cli.TestFileHelpers
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ProjectConfigValidScenariosTest {
    private lateinit var tempDir: Path
    private lateinit var runner: ScenarioRunner
    private val projectsDir = ScenarioLoader.discoverProjectsDir()

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("project-valid-scenarios")
        runner = ScenarioRunner(tempDir)
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    @Test
    fun `base project - valid scenarios`() = runTest { runValid("base") }

    @Test
    fun `base-kmp project - valid scenarios`() = runTest { runValid("base-kmp") }

    @Test
    fun `coinbox project - valid scenarios`() = runTest { runValid("coinbox") }

    @Test
    fun `gridmon project - valid scenarios`() = runTest { runValid("gridmon") }

    @Test
    fun `ledger project - valid scenarios`() = runTest { runValid("ledger") }

    @Test
    fun `navigator project - valid scenarios`() = runTest { runValid("navigator") }

    @Test
    fun `telecom project - valid scenarios`() = runTest { runValid("telecom") }

    @Test
    fun `wallet project - valid scenarios`() = runTest { runValid("wallet") }

    @Test
    fun `ai-context-quality project - valid scenarios`() = runTest { runValid("ai-context-quality") }

    private suspend fun runValid(project: String) {
        val failures = runner.runValidScenarios(projectsDir, project)
        assertTrue(failures.isEmpty(), "Failed valid scenarios for $project:\n${failures.joinToString("\n")}")
    }
}
