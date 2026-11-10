package io.karandash.contracts.fw

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.config.ConfigValidateCommand
import io.karandash.cli.commands.init.InitCommand
import io.karandash.cli.commands.simulate.SimulateCommand
import io.karandash.contracts.support.FakeConfigRenderer
import io.karandash.contracts.support.createContractTempDir
import io.karandash.contracts.support.deleteRecursively
import io.karandash.contracts.support.writeTextFile
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CLI commands that look finished but are stubs or ignore their inputs.
 *
 * - `init --format kts` writes to the default renderer's path (`config.yaml`).
 * - `config validate` reports "Config is valid" with unresolved `${VAR}`s and no warning; `--strict` makes them fail.
 * - `simulate` prints a static note instead of evaluating the policy.
 */
class StubCommandsContractTest {
    private val tempDir = createContractTempDir("stub-commands")

    @AfterTest
    fun cleanup() = deleteRecursively(tempDir)

    @Test
    fun `init --format kts without --output writes the kts default path`() =
        runTest {
            val yamlPath = Path(tempDir, "config.yaml")
            val ktsPath = Path(tempDir, "config.kts")
            val bootstrap = PluginBootstrap(
                onFormatRegistryCreated = { registry ->
                    // yaml first: it becomes ConfigFormatRegistry.defaultRenderer()
                    registry.registerRenderer(FakeConfigRenderer("yaml", yamlPath.toString()))
                    registry.registerRenderer(FakeConfigRenderer("kts", ktsPath.toString()))
                },
            )
            bootstrap.loadPlugins()
            try {
                InitCommand(bootstrap).test(listOf("--format", "kts"))
                assertTrue(
                    SystemFileSystem.exists(ktsPath),
                    "init --format kts did not write $ktsPath",
                )
                assertFalse(
                    SystemFileSystem.exists(yamlPath),
                    "init --format kts wrote KTS content to the YAML default path $yamlPath",
                )
            } finally {
                bootstrap.shutdown()
            }
        }

    @Test
    fun `config validate warns on unresolved env var and fails with --strict`() =
        runTest {
            val configPath = Path(tempDir, "config.yaml")
            writeTextFile(
                configPath,
                """
                rules:
                  commit-messages:
                    pattern: "${'$'}{KARANDASH_UNSET_VAR}"
                """.trimIndent(),
            )
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            try {
                val lenient = ConfigValidateCommand(bootstrap).test(listOf("--file", configPath.toString()))
                assertEquals(0, lenient.statusCode, "config validate failed without --strict: ${lenient.output}")
                assertTrue(
                    lenient.stderr.contains("KARANDASH_UNSET_VAR"),
                    "config validate did not warn about the unresolved var: ${lenient.output}",
                )
                val strict = ConfigValidateCommand(bootstrap)
                    .test(listOf("--file", configPath.toString(), "--strict"))
                assertNotEquals(
                    0,
                    strict.statusCode,
                    "config validate --strict accepted unresolved \${KARANDASH_UNSET_VAR}: ${strict.output}",
                )
            } finally {
                bootstrap.shutdown()
            }
        }

    @Test
    fun `simulate evaluates the configured approval policy`() {
        val configPath = Path(tempDir, "config.yaml")
        writeTextFile(
            configPath,
            """
            rules:
              approval:
                policy:
                  all:
                    - minApprovals: 2
            """.trimIndent(),
        )
        val result = SimulateCommand().test(listOf("--file", configPath.toString(), "--approvers", "alice"))
        assertFalse(
            result.output.contains("Full simulation requires"),
            "simulate printed the static stub note instead of evaluating the policy: ${result.output}",
        )
    }
}
