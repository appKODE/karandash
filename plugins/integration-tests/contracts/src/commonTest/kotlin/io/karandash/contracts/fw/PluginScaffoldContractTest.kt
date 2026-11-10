package io.karandash.contracts.fw

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.generate.GenerateCommand
import io.karandash.contracts.support.createContractTempDir
import io.karandash.contracts.support.deleteRecursively
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `generate` scaffolding disagrees with its own help and exit contract.
 *
 * - Help advertises `provider`, but `--type` only accepts rule/reporter/platform-service.
 * - Invalid input is echoed to stderr and the command exits 0.
 */
class PluginScaffoldContractTest {
    private val tempDir = createContractTempDir("scaffold")

    @AfterTest
    fun cleanup() = deleteRecursively(tempDir)

    @Test
    fun `generate --type provider scaffolds a project`() =
        runTest {
            val result = GenerateCommand(PluginBootstrap()).test(
                listOf("--type", "provider", "--name", "probe", "--output", tempDir.toString()),
            )

            assertEquals(0, result.statusCode, "generate --type provider rejected: ${result.output}")
            assertTrue(
                SystemFileSystem.exists(Path(tempDir, "probe-plugin")),
                "generate --type provider did not create probe-plugin under $tempDir",
            )
        }

    @Test
    fun `generate with invalid plugin name exits non-zero`() =
        runTest {
            val result = GenerateCommand(PluginBootstrap()).test(
                listOf("--type", "rule", "--name", "BadName", "--output", tempDir.toString()),
            )

            assertNotEquals(
                0,
                result.statusCode,
                "generate with invalid name 'BadName' exited 0: ${result.output}",
            )
        }
}
