package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.doctor.DoctorCheckResult
import io.karandash.cli.commands.doctor.DoctorCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.Config
import io.karandash.http.NetworkPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoctorCommandTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("karandash-doctor-test")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    /**
     * Helper: create a config file at the standard discovery path within tempDir.
     */
    private fun createConfigInDir(content: String) {
        val configDir = Path(tempDir, ".karandash")
        SystemFileSystem.createDirectories(configDir)
        TestFileHelpers.writeFile(Path(configDir, "config.kts"), content)
    }

    private val validConfigDsl = """
        rules {
            titleFormat {
                pattern = "^(feat|fix): .+"
            }
            draftPrevention {
                blockDraft = true
            }
        }
    """.trimIndent()

    private val invalidConfigDsl = """
        this is not valid { config syntax !!!
    """.trimIndent()

    private val configWithAuth = """
        platforms {
            github {
                token = "test-token-123"
            }
        }
        rules {
            titleFormat {
                pattern = "^feat: .+"
            }
        }
    """.trimIndent()

    // -------------------------------------------------------
    // Config validation tests
    // -------------------------------------------------------

    @Test
    fun `doctor lists the formats outputs and the pairs a run selects`() =
        runTest {
            createConfigInDir(validConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()

            val result = DoctorCommand(bootstrap, workingDir = tempDir).test(listOf("--offline"))

            assertTrue(result.output.contains("[Report Formats]"), result.output)
            assertTrue(Regex("Formats: .*console").containsMatchIn(result.output), result.output)
            assertTrue(Regex("Outputs: .*stdout").containsMatchIn(result.output), result.output)
            assertTrue(Regex("Selected: \\w+ -> \\w+").containsMatchIn(result.output), result.output)
        }

    @Test
    fun `config check passes with valid config`() =
        runTest {
            createConfigInDir(validConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertTrue(result.output.contains("[Config]"))
            assertTrue(result.output.contains("OK:"))
            assertTrue(result.output.contains("2 rule(s)"))
            assertTrue(result.output.contains("All checks passed"))
            assertEquals(0, result.statusCode)
        }

    @Test
    fun `config check fails with invalid config - exit code 2`() =
        runTest {
            createConfigInDir(invalidConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertTrue(result.output.contains("[Config]"))
            assertTrue(result.output.contains("FAIL:"))
            assertTrue(result.output.contains("Config parse error (fatal)"))
            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        }

    @Test
    fun `no config found is a warning not a failure`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertTrue(result.output.contains("[Config]"))
            assertTrue(result.output.contains("WARN: No config file, zero-config defaults apply."), result.output)
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        }

    // -------------------------------------------------------
    // Auth check tests
    // -------------------------------------------------------

    @Test
    fun `auth check reports configured providers`() =
        runTest {
            createConfigInDir(configWithAuth)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)

            // --offline: no live GitHub call from a unit test (DoctorAuthCheckTest covers the API check)
            val result = command.test(listOf("--offline"))

            assertTrue(result.output.contains("[Auth]"))
            assertTrue(result.output.contains("github: configured"))
        }

    @Test
    fun `auth check skips when no auth configured`() =
        runTest {
            createConfigInDir(validConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertTrue(result.output.contains("[Auth]"))
            assertTrue(result.output.contains("SKIP: No auth providers configured"))
        }

    // -------------------------------------------------------
    // Plugin check tests
    // -------------------------------------------------------

    @Test
    fun `plugin check reports no plugins when none configured`() =
        runTest {
            createConfigInDir(validConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertTrue(result.output.contains("[Plugins]"))
            assertTrue(result.output.contains("No plugins configured") || result.output.contains("OK:"))
        }

    // -------------------------------------------------------
    // Output format tests
    // -------------------------------------------------------

    @Test
    fun `output includes all section headers`() =
        runTest {
            createConfigInDir(validConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertTrue(result.output.contains("Karandash Doctor"))
            assertTrue(result.output.contains("[Config]"))
            assertTrue(result.output.contains("[Auth]"))
            assertTrue(result.output.contains("[Plugins]"))
            assertTrue(result.output.contains("Result:"))
        }

    // -------------------------------------------------------
    // Internal check methods - unit tests
    // -------------------------------------------------------

    @Test
    fun `checkConfig returns Pass with valid config`() =
        runTest {
            createConfigInDir(validConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.checkConfig()

            assertTrue(result is DoctorCheckResult.Pass)
            assertTrue(result.message.contains("2 rule(s)"))
            assertTrue(result.config != null)
        }

    @Test
    fun `checkAuth takes allow-private-network from the parsed config`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val policy = NetworkPolicy()
            withContext(policy) {
                command.checkAuth(DoctorCheckResult.Pass("ok", Config(allowPrivateNetwork = true), JsonObject(emptyMap())))
            }
            assertTrue(policy.allowPrivateNetwork)
            withContext(policy) { command.checkAuth(DoctorCheckResult.Pass("ok", Config(), JsonObject(emptyMap()))) }
            assertFalse(policy.allowPrivateNetwork)
        }

    @Test
    fun `checkConfig resolves extends - a parent's allow-private-network and auth count`() =
        runTest {
            val configDir = Path(tempDir, ".karandash")
            SystemFileSystem.createDirectories(configDir)
            TestFileHelpers.writeFile(
                Path(configDir, "base.yaml"),
                "allow-private-network: true\nauth:\n  gitlab:\n    url: \"https://gitlab.example.com\"\n    token: \"base\"\n" +
                    "  github:\n    url: \"https://github.example.com\"\n    token: \"base\"\n",
            )
            TestFileHelpers.writeFile(
                Path(configDir, "config.yaml"),
                "extends: base.yaml\nauth:\n  gitlab:\n    url: \"https://gitlab.example.com\"\n    token: \"root\"\n",
            )
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val result = DoctorCommand(bootstrap, workingDir = tempDir).checkConfig()

            assertTrue(result is DoctorCheckResult.Pass, result.message)
            assertEquals(true, result.config?.allowPrivateNetwork)
            val auth = (result.authConfig as? JsonObject)?.get("auth") as? JsonObject
            assertEquals(setOf("gitlab", "github"), auth?.keys)
            assertEquals("root", ((auth?.get("gitlab") as? JsonObject)?.get("token") as? JsonPrimitive)?.content)
        }

    @Test
    fun `doctor lists the token variables a single yaml config references`() =
        runTest {
            val configDir = Path(tempDir, ".karandash")
            SystemFileSystem.createDirectories(configDir)
            TestFileHelpers.writeFile(
                Path(configDir, "config.yaml"),
                "cache:\n  enabled: false\nplatforms:\n  gitlab:\n    token: \"\${KARANDASH_DOCTOR_TEST_TOKEN}\"\n",
            )
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val result = DoctorCommand(bootstrap, workingDir = tempDir).test(listOf("--offline"))

            assertTrue(result.output.contains("KARANDASH_DOCTOR_TEST_TOKEN: missing"), result.output)
        }

    @Test
    fun `checkConfig returns Fatal with unparseable config`() =
        runTest {
            createConfigInDir(invalidConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.checkConfig()

            assertTrue(result is DoctorCheckResult.Fatal)
            assertTrue(result.message.contains("Failed to parse"))
        }

    @Test
    fun `checkConfig reports a missing plugin under on-missing fail as a failed check`() =
        runTest {
            val configDir = Path(tempDir, ".karandash")
            SystemFileSystem.createDirectories(configDir)
            TestFileHelpers.writeFile(
                Path(configDir, "config.yaml"),
                "plugins:\n  on-missing: fail\nrules:\n  no-such-module-xyz: {}\n",
            )
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val result = DoctorCommand(bootstrap, workingDir = tempDir).checkConfig()

            assertTrue(result is DoctorCheckResult.Fail, "${result::class.simpleName}: ${result.message}")
            assertTrue(result.message.contains("noSuchModuleXyz"), result.message)
            assertFalse(result.message.contains("Failed to parse"), result.message)
        }

    @Test
    fun `no config found keeps the init advice in the hint and prints it as how to fix`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.checkConfig()

            assertTrue(result is DoctorCheckResult.Warn)
            assertEquals("No config file, zero-config defaults apply.", result.message)
            assertEquals("Run 'karandash init' to create one.", result.hint)
            val output = command.test(emptyList()).output
            assertTrue(
                "  WARN: No config file, zero-config defaults apply.\n    → How to fix: Run 'karandash init' to create one.\n" in output,
                output
            )
        }

    @Test
    fun `a config parse error prints its hint as how to fix`() =
        runTest {
            val configDir = Path(tempDir, ".karandash")
            SystemFileSystem.createDirectories(configDir)
            TestFileHelpers.writeFile(Path(configDir, "config.yaml"), "policies: x\n")
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.checkConfig()

            assertTrue(result is DoctorCheckResult.Fatal, "$result")
            assertEquals("Write 'policies' as a list of entries, each with a 'when' condition.", result.hint)
            val output = command.test(emptyList()).output
            assertTrue("    → How to fix: Write 'policies' as a list of entries, each with a 'when' condition.\n" in output, output)
        }

    @Test
    fun `checkConfig returns Warn when no config found`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.checkConfig()

            assertTrue(result is DoctorCheckResult.Warn)
            assertTrue(result.message.contains("zero-config defaults apply"))
        }

    @Test
    fun `checkAuth returns empty when config has no auth`() =
        runTest {
            createConfigInDir(validConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val configResult = command.checkConfig()
            val authResults = command.checkAuth(configResult)

            assertTrue(authResults.isEmpty())
        }

    @Test
    fun `checkAuth returns empty when config check failed`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val failResult = DoctorCheckResult.Fail("no config")
            val authResults = command.checkAuth(failResult)

            assertTrue(authResults.isEmpty())
        }

    @Test
    fun `checkPlugins returns results even without config`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val pluginResults = command.checkPlugins(bootstrap)

            assertTrue(pluginResults.isNotEmpty())
        }

    // -------------------------------------------------------
    // Exit code tests
    // -------------------------------------------------------

    @Test
    fun `all checks pass returns exit code 0`() =
        runTest {
            createConfigInDir(validConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertEquals(0, result.statusCode)
        }

    @Test
    fun `fatal config error returns exit code 2`() =
        runTest {
            createConfigInDir(invalidConfigDsl)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        }

    @Test
    fun `non-fatal failures return exit code 1`() =
        runTest {
            val configDir = Path(tempDir, ".karandash")
            SystemFileSystem.createDirectories(configDir)
            TestFileHelpers.writeFile(
                Path(configDir, "config.yaml"),
                "plugins:\n  on-missing: fail\nrules:\n  no-such-module-xyz: {}\n",
            )
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode)
        }
}
