package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.doctor.DoctorCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.loader.PluginDiscovery
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Doctor sections that only print on demand (`--plugins`) or when a check fails. */
class DoctorCommandReportingTest {
    private val dir = TestFiles.createTestTempDirectory("karandash-doctor-reporting")
    private val pluginDir = Path(dir, "plugins")

    private suspend fun bootstrap() =
        PluginBootstrap(envReader = { if (it == PluginDiscovery.PLUGIN_DIR_ENV) pluginDir.toString() else null })
            .also { it.loadPlugins() }

    private fun config(yaml: String) = TestFiles.writeText(Path(dir, ".karandash", "config.yaml"), yaml)

    @Test
    fun `plugins flag prints the module table`() =
        runTest {
            config("rules:\n  title-format:\n    pattern: \"^feat: .+\"\n")
            val result = DoctorCommand(bootstrap(), workingDir = dir).test(listOf("--plugins", "--offline"))

            assertTrue("[Plugin Details]" in result.output, result.output)
            assertTrue(Regex("""title-format\S*\s+OK""").containsMatchIn(result.output), result.output)
        }

    @Test
    fun `a policy with an unknown predicate fails the run`() =
        runTest {
            config("rules:\n  title-format:\n    pattern: \"^feat: .+\"\npolicies:\n  - when: \"unknown_predicate_xyz\"\n")
            val result = DoctorCommand(bootstrap(), workingDir = dir).test(listOf("--offline"))

            assertTrue("FAIL: Policy expression 'unknown_predicate_xyz'" in result.output, result.output)
            assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode)
        }

    @Test
    fun `a plugin that fails to load is reported as a failed check`() =
        runTest {
            config("rules:\n  title-format:\n    pattern: \"^feat: .+\"\n")
            TestFiles.writeText(Path(pluginDir, "broken.wasm"), "not a wasm module")
            val result = DoctorCommand(bootstrap(), workingDir = dir).test(listOf("--offline"))

            assertTrue(Regex("""FAIL: \[E0300] Failed to load plugin '.*broken\.wasm'""").containsMatchIn(result.output), result.output)
            assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode)
        }
}
