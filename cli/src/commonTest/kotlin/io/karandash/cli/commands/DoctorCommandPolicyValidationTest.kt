package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.doctor.DoctorCheckResult
import io.karandash.cli.commands.doctor.DoctorCommand
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DoctorCommandPolicyValidationTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("karandash-doctor-policy-test")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    private fun createConfig(content: String) {
        val configDir = Path(tempDir, ".karandash")
        SystemFileSystem.createDirectories(configDir)
        TestFileHelpers.writeFile(Path(configDir, "config.yaml"), content)
    }

    private val configWithValidPolicy = """
        rules:
          title-format:
            pattern: "^feat: .+"
        policies:
          - when: "is_draft"
            enabled: false
    """.trimIndent()

    private val configWithUnknownPredicate = """
        rules:
          title-format:
            pattern: "^feat: .+"
        policies:
          - when: "unknown_predicate_xyz"
    """.trimIndent()

    private val configWithMultiplePolicies = """
        rules:
          title-format:
            pattern: "^feat: .+"
        policies:
          - when: "is_draft"
            enabled: false
          - when: "branch_matches(feature/*)"
            severity: "warning"
    """.trimIndent()

    private val configNoPolicies = """
        rules:
          title-format:
            pattern: "^feat: .+"
    """.trimIndent()

    // -------------------------------------------------------
    // checkPolicies unit tests
    // -------------------------------------------------------

    @Test
    fun `checkPolicies returns empty when config check failed`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val failResult = DoctorCheckResult.Fail("no config")

            val results = command.checkPolicies(failResult)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `checkPolicies returns empty when no rawConfig`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val passWithoutRaw = DoctorCheckResult.Pass("ok", rawConfig = null)

            val results = command.checkPolicies(passWithoutRaw)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `checkPolicies returns empty when rawConfig has no policies key`() =
        runTest {
            createConfig(configNoPolicies)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val configResult = command.checkConfig()

            val results = command.checkPolicies(configResult)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `checkPolicies returns Pass for valid known predicates`() =
        runTest {
            createConfig(configWithValidPolicy)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val configResult = command.checkConfig()

            val results = command.checkPolicies(configResult)

            assertTrue(results.isNotEmpty())
            assertTrue(results.all { it is DoctorCheckResult.Pass }, "All results should be Pass for valid predicates")
            assertTrue(results.any { it.message.contains("valid") })
        }

    @Test
    fun `checkPolicies returns Fail for unknown predicates`() =
        runTest {
            createConfig(configWithUnknownPredicate)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val configResult = command.checkConfig()

            val results = command.checkPolicies(configResult)

            assertTrue(results.isNotEmpty())
            assertTrue(results.any { it is DoctorCheckResult.Fail }, "Should have at least one Fail for unknown predicate")
            assertTrue(results.any { it.message.contains("unknown_predicate_xyz") })
        }

    @Test
    fun `unknown predicate failure names the problem and hints the known predicates`() =
        runTest {
            createConfig(configWithUnknownPredicate)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)

            val fail = command.checkPolicies(command.checkConfig()).filterIsInstance<DoctorCheckResult.Fail>().first()

            assertFalse("Known:" in fail.message, fail.message)
            assertTrue(fail.message.endsWith("unknown predicate 'unknown_predicate_xyz'"), fail.message)
            val hint = assertNotNull(fail.hint)
            assertTrue(hint.startsWith("Replace 'unknown_predicate_xyz' with one of: "), hint)
            assertTrue("is_draft" in hint, hint)
        }

    @Test
    fun `checkPolicies accepts a predicate a rule module registers`() =
        runTest {
            createConfig(
                """
                rules:
                  title-format:
                    pattern: "^feat: .+"
                policies:
                  - when: "has_tag(android)"
                    enabled: false
                """.trimIndent(),
            )
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)

            val results = command.checkPolicies(command.checkConfig())

            assertTrue(results.all { it is DoctorCheckResult.Pass }, results.joinToString { it.message })
        }

    @Test
    fun `checkPolicies passes for multiple valid policies`() =
        runTest {
            createConfig(configWithMultiplePolicies)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val configResult = command.checkConfig()

            val results = command.checkPolicies(configResult)

            assertTrue(results.isNotEmpty())
            assertTrue(results.all { it is DoctorCheckResult.Pass })
        }

    // -------------------------------------------------------
    // Full doctor output integration
    // -------------------------------------------------------

    @Test
    fun `doctor output includes Policy Validation section`() =
        runTest {
            createConfig(configNoPolicies)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertTrue(result.output.contains("[Policy Validation]"))
        }

    @Test
    fun `doctor shows SKIP when no policies configured`() =
        runTest {
            createConfig(configNoPolicies)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertTrue(result.output.contains("[Policy Validation]"))
            assertTrue(result.output.contains("SKIP: No policies configured"))
        }

    @Test
    fun `doctor shows OK for valid policy expressions`() =
        runTest {
            createConfig(configWithValidPolicy)
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val command = DoctorCommand(bootstrap, workingDir = tempDir)
            val result = command.test(emptyList())

            assertTrue(result.output.contains("[Policy Validation]"))
            assertTrue(result.output.contains("OK:"))
        }
}
