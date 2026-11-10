package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.OFFLINE_ENV
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.errors.ExitCodes
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidateCommandIntegrationTest {
    private lateinit var tempDir: Path
    private var fileCounter = 0

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("validate-command-integration")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    /**
     * Helper: create a temporary config file with the given DSL content.
     */
    private fun createConfigFile(content: String): Path = writeTempFile("karandash-config", ".kts", content)

    /**
     * Helper: create a temporary JSON config file.
     */
    private fun createJsonConfigFile(content: String): Path = writeTempFile("karandash-config", ".json", content)

    /**
     * Helper: create a temporary MR JSON file.
     */
    private fun createMrFile(json: String): Path = writeTempFile("karandash-mr", ".json", json)

    private fun createOutputFile(suffix: String): Path = Path(tempDir, "karandash-output-${fileCounter++}$suffix")

    private fun writeTempFile(
        prefix: String,
        suffix: String,
        content: String
    ): Path {
        val file = Path(tempDir, "$prefix-${fileCounter++}$suffix")
        TestFileHelpers.writeFile(file, content)
        return file
    }

    /**
     * A minimal valid config DSL that accepts any title matching "feat: ..."
     */
    private val passingConfigDsl = """
        rules {
            titleFormat {
                pattern = "^(feat|fix): .+"
            }
        }
    """.trimIndent()

    /**
     * A config DSL that requires titles to start with "STRICT-" which our test MR will fail.
     */
    private val failingConfigDsl = """
        rules {
            titleFormat {
                pattern = "^STRICT-.*"
            }
        }
    """.trimIndent()

    /**
     * A minimal valid JSON config that accepts any title with min-length of 1.
     */
    private val passingJsonConfig = """
{
  "rules": {
    "titleFormat": {
      "pattern": "^(feat|fix): .+",
      "min-length": 1
    }
  }
}
    """.trimIndent()

    /**
     * A JSON config that requires titles to be 100+ chars which our test MR will fail.
     */
    private val failingJsonConfig = """
{
  "rules": {
    "titleFormat": {
      "pattern": "^.{100,}$",
      "min-length": 100
    }
  }
}
    """.trimIndent()

    /**
     * Valid MR JSON that matches the passingConfigDsl.
     */
    private val validMrJson = """
        {
            "id": "1",
            "iid": 100,
            "title": "feat: add new feature",
            "description": "Test description",
            "source_branch": "feature/test",
            "target_branch": "commonMain",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "feat: implement feature",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
    """.trimIndent()

    /**
     * Run the ValidateCommand with the given args, returning the exit code.
     * Returns 0 if the command completes without throwing ProgramResult.
     */
    private suspend fun runCommand(vararg args: String): Int {
        val bootstrap = PluginBootstrap()
        bootstrap.loadPlugins()
        val result = ValidateCommand(bootstrap).test(args.joinToString(" "))
        return result.statusCode
    }

    /**
     * Runs command with environment reads disabled to keep "no auth" tests deterministic in CI.
     */
    private suspend fun runCommandWithNoAuth(vararg args: String): Int {
        // Platforms start without a token; API URLs on a `.invalid` host keep the fetch offline.
        val bootstrap = PluginBootstrap(envReader = OFFLINE_ENV::get)
        bootstrap.loadPlugins()
        val result = ValidateCommand(bootstrap).test(args.joinToString(" "))
        return result.statusCode
    }

    // -------------------------------------------------------
    // File mode tests
    // -------------------------------------------------------

    @Test
    fun `file mode - validation passes with valid MR`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString()
            )

            assertEquals(ExitCodes.SUCCESS, exitCode)
        }

    @Test
    fun `file mode - validation fails returns exit code 1`() =
        runTest {
            val configFile = createConfigFile(failingConfigDsl)
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString()
            )

            assertEquals(ExitCodes.VALIDATION_FAILED, exitCode)
        }

    @Test
    fun `file mode - json format produces output without error`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--format",
                "json"
            )

            assertEquals(ExitCodes.SUCCESS, exitCode)
        }

    @Test
    fun `file mode - json format with failed validation returns exit code 1`() =
        runTest {
            val configFile = createConfigFile(failingConfigDsl)
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--format",
                "json"
            )

            assertEquals(ExitCodes.VALIDATION_FAILED, exitCode)
        }

    // -------------------------------------------------------
    // JSON config file mode tests
    // -------------------------------------------------------

    @Test
    fun `file mode with JSON config - validation passes`() =
        runTest {
            val configFile = createJsonConfigFile(passingJsonConfig)
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--format",
                "console"
            )

            assertEquals(ExitCodes.SUCCESS, exitCode)
        }

    @Test
    fun `file mode with JSON config - validation fails returns exit code 1`() =
        runTest {
            val configFile = createJsonConfigFile(failingJsonConfig)
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--format",
                "console"
            )

            assertEquals(ExitCodes.VALIDATION_FAILED, exitCode)
        }

    @Test
    fun `file mode with invalid JSON config returns config error`() =
        runTest {
            val configFile = createJsonConfigFile("{ invalid json }")
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--format",
                "console"
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    // -------------------------------------------------------
    // Mode detection error tests
    // -------------------------------------------------------

    @Test
    fun `no mode specified - returns config error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--no-auto-detect"
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    @Test
    fun `both modes specified - returns config error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--mr-id",
                "123",
                "--platform",
                "gitlab",
                "--project",
                "group/repo"
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    @Test
    fun `live mode with missing platform - returns config error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr-id",
                "123",
                "--project",
                "group/repo"
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    @Test
    fun `live mode with missing project - returns config error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr-id",
                "123",
                "--platform",
                "gitlab"
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    @Test
    fun `live mode with missing mr-id - returns config error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--platform",
                "gitlab",
                "--project",
                "group/repo"
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    // -------------------------------------------------------
    // Live mode - provider error tests
    // -------------------------------------------------------

    @Test
    fun `live mode with unknown platform - returns provider error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr-id",
                "123",
                "--platform",
                "unknown-platform",
                "--project",
                "group/repo"
            )

            assertEquals(ExitCodes.PROVIDER_ERROR, exitCode)
        }

    @Test
    fun `live mode with no auth configured - returns provider error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)

            val exitCode = runCommandWithNoAuth(
                "--config",
                configFile.toString(),
                "--mr-id",
                "123",
                "--platform",
                "gitlab",
                "--project",
                "group/repo"
            )

            assertEquals(ExitCodes.PROVIDER_ERROR, exitCode)
        }

    // -------------------------------------------------------
    // Invalid format test
    // -------------------------------------------------------

    @Test
    fun `invalid format returns config error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--format",
                "xml"
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    // -------------------------------------------------------
    // Invalid MR JSON test
    // -------------------------------------------------------

    @Test
    fun `invalid MR JSON returns config error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile("{ invalid json }")

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString()
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    // -------------------------------------------------------
    // Default format is console
    // -------------------------------------------------------

    @Test
    fun `default format is console and works correctly`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile(validMrJson)

            // No --format specified, should use console (default) and succeed
            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString()
            )

            assertEquals(ExitCodes.SUCCESS, exitCode)
        }

    // -------------------------------------------------------
    // Output file tests
    // -------------------------------------------------------

    @Test
    fun `output flag writes SARIF to file`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile(validMrJson)
            val outputFile = createOutputFile(".sarif")

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--format",
                "sarif",
                "--output",
                outputFile.toString()
            )

            assertEquals(ExitCodes.SUCCESS, exitCode)
            assertTrue(TestFileHelpers.exists(outputFile), "Output file should exist")
            val content = TestFileHelpers.readFile(outputFile)
            assertTrue(content.isNotEmpty(), "Output file should not be empty")
            assertTrue(content.contains("\"version\": \"2.1.0\""), "SARIF output should contain version")
        }

    @Test
    fun `output flag writes JSON to file`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile(validMrJson)
            val outputFile = createOutputFile(".json")

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--format",
                "json",
                "--output",
                outputFile.toString()
            )

            assertEquals(ExitCodes.SUCCESS, exitCode)
            assertTrue(TestFileHelpers.exists(outputFile), "Output file should exist")
            val content = TestFileHelpers.readFile(outputFile)
            assertTrue(content.isNotEmpty(), "Output file should not be empty")
            assertTrue(content.contains("\"passed\""), "JSON output should contain passed field")
        }

    @Test
    fun `without output flag output goes to stdout`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile(validMrJson)

            // This test verifies existing behavior - no output file means stdout
            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--mr",
                mrFile.toString(),
                "--format",
                "json"
            )

            assertEquals(ExitCodes.SUCCESS, exitCode)
        }

    // -------------------------------------------------------
    // PR URL mode tests
    // -------------------------------------------------------

    @Test
    fun `pr mode with invalid URL returns config error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--pr",
                "https://example.com/invalid/url"
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    @Test
    fun `pr mode cannot be combined with file mode`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)
            val mrFile = createMrFile(validMrJson)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--pr",
                "https://gitlab.invalid/group/project/-/merge_requests/123",
                "--mr",
                mrFile.toString()
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    @Test
    fun `pr mode cannot be combined with live mode parameters`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)

            val exitCode = runCommand(
                "--config",
                configFile.toString(),
                "--pr",
                "https://gitlab.invalid/group/project/-/merge_requests/123",
                "--mr-id",
                "123",
                "--platform",
                "gitlab",
                "--project",
                "group/repo"
            )

            assertEquals(ExitCodes.CONFIG_ERROR, exitCode)
        }

    @Test
    fun `pr mode with no auth returns provider error exit code`() =
        runTest {
            val configFile = createConfigFile(passingConfigDsl)

            // Valid URL but no authentication configured
            // Provider will fail to initialize and be skipped, resulting in PROVIDER_ERROR
            val exitCode = runCommandWithNoAuth(
                "--config",
                configFile.toString(),
                "--pr",
                "https://gitlab.invalid/group/project/-/merge_requests/123"
            )

            assertEquals(ExitCodes.PROVIDER_ERROR, exitCode)
        }

    @Test
    fun `pr mode works without config file in zero-config mode`() =
        runTest {
            // This test validates that --pr works without a config file
            // Provider will fail to initialize (no auth) and be skipped, resulting in PROVIDER_ERROR
            val exitCode = runCommandWithNoAuth(
                "--pr",
                "https://gitlab.invalid/group/project/-/merge_requests/123"
            )

            // Should fail with PROVIDER_ERROR (no provider available due to missing auth)
            // This proves zero-config mode works for --pr (parses URL correctly even without provider)
            assertEquals(ExitCodes.PROVIDER_ERROR, exitCode)
        }
}
