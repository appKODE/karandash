package io.karandash.cli.commands.projects

import io.karandash.cli.TestFileHelpers
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ProjectConfigInvalidScenariosTest {
    private lateinit var tempDir: Path
    private lateinit var runner: ScenarioRunner
    private val projectsDir = ScenarioLoader.discoverProjectsDir()

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("project-invalid-scenarios")
        runner = ScenarioRunner(tempDir)
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    @Test
    fun `base project - invalid scenarios`() = runTest { runInvalid("base") }

    @Test
    fun `base-kmp project - invalid scenarios`() = runTest { runInvalid("base-kmp") }

    @Test
    fun `coinbox project - invalid scenarios`() = runTest { runInvalid("coinbox") }

    @Test
    fun `gridmon project - invalid scenarios`() = runTest { runInvalid("gridmon") }

    @Test
    fun `ledger project - invalid scenarios`() = runTest { runInvalid("ledger") }

    @Test
    fun `navigator project - invalid scenarios`() = runTest { runInvalid("navigator") }

    @Test
    fun `telecom project - invalid scenarios`() = runTest { runInvalid("telecom") }

    @Test
    fun `wallet project - invalid scenarios`() = runTest { runInvalid("wallet") }

    @Test
    fun `ai-context-quality project - invalid scenarios`() = runTest { runInvalid("ai-context-quality") }

    private suspend fun runInvalid(project: String) {
        val failures = runner.runInvalidScenarios(projectsDir, project)
        assertTrue(failures.isEmpty(), "Failed invalid scenarios for $project:\n${failures.joinToString("\n")}")
    }
}
