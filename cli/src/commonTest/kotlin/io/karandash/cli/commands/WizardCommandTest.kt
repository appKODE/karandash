package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.config.ConfigValidateCommand
import io.karandash.cli.commands.wizard.WizardCommand
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import io.karandash.cli.errors.ExitCodes
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `wizard` answers platform, profile and confirm prompts, then writes a config that `config validate` accepts. */
class WizardCommandTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-wizard")
    private val bootstrap = PluginBootstrap(envReader = { null }, onFormatRegistryCreated = ::registerBuiltInRenderers)
    private val out = Path(tempDir, "config.yaml")

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private suspend fun wizard(vararg answers: String): TerminalRecorder {
        bootstrap.loadPluginsFromDirs(emptyList())
        val recorder = TerminalRecorder().apply { inputLines = answers.toMutableList() }
        val result = WizardCommand(bootstrap, Terminal(terminalInterface = recorder))
            .test(listOf("--skip-detection", "--format", "yaml", "--output", out.toString()))
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        return recorder
    }

    @Test
    fun `confirmed wizard writes a valid config`() =
        runTest {
            val recorder = wizard("1", "1", "y")
            assertContains(recorder.stdout(), "Done! Configuration written to $out")
            val validated = ConfigValidateCommand(bootstrap).test(listOf("--file", out.toString()))
            assertEquals(ExitCodes.SUCCESS, validated.statusCode, validated.output)
        }

    @Test
    fun `declined wizard writes nothing`() =
        runTest {
            val recorder = wizard("1", "1", "n")
            assertContains(recorder.stdout(), "Setup cancelled.")
            assertFalse(SystemFileSystem.exists(out))
        }

    @Test
    fun `unknown format is a usage error before any prompt`() =
        runTest {
            bootstrap.loadPluginsFromDirs(emptyList())
            val recorder = TerminalRecorder().apply { inputLines = mutableListOf("1", "1", "y") }
            val result = WizardCommand(bootstrap, Terminal(terminalInterface = recorder))
                .test(listOf("--skip-detection", "--format", "xml", "--output", out.toString()))

            assertEquals(1, result.statusCode, result.output)
            assertContains(result.stderr, "--format")
            assertFalse(SystemFileSystem.exists(out))
        }
}
