package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.validate.ValidateExtendsCommand
import io.karandash.cli.errors.ExitCodes
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `validate-extends --list-sets` prints the sets of `.karandash/extends-manifest.yaml` without needing a config. */
class ValidateExtendsListSetsTest {
    private val testDir = TestFileHelpers.createTempDir("validate-extends-list-sets")

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(testDir)

    private suspend fun command(): ValidateExtendsCommand {
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPlugins()
        return ValidateExtendsCommand(bootstrap, workingDir = testDir)
    }

    @Test
    fun `list-sets prints every set with its entries`() =
        runTest {
            SystemFileSystem.createDirectories(Path(testDir, ".karandash"))
            TestFileHelpers.writeFile(
                Path(testDir, ".karandash", "extends-manifest.yaml"),
                """
                extends-sets:
                  standard:
                    - "https://company.example/base.yaml"
                    - url: "https://company.example/security.yaml"
                  minimal:
                    - "https://company.example/base.yaml"
                """.trimIndent(),
            )

            val result = command().test("--list-sets")

            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            val expected =
                """
                Extends sets (.karandash/extends-manifest.yaml):
                  @standard
                    - https://company.example/base.yaml
                    - https://company.example/security.yaml
                  @minimal
                    - https://company.example/base.yaml
                """.trimIndent()
            assertEquals(expected, result.stdout.trim())
        }

    @Test
    fun `list-sets without a manifest is a config error`() =
        runTest {
            val result = command().test("--list-sets")

            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
            assertTrue("Extends Chain Validation" !in result.stdout, result.stdout)
        }
}
