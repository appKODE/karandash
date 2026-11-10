package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.validate.ValidateExtendsCommand
import io.karandash.cli.errors.ExitCodes
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidateExtendsAutoDiscoveryTest {
    @Test
    fun `validate-extends without config file uses auto-discovery`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val testDir = TestFileHelpers.createTempDir("validate-extends-discovery")
            try {
                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(
                    configFile,
                    """
                rules:
                  title-format:
                    pattern: "^feat:"
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap, workingDir = testDir)
                val result = command.test("")

                assertEquals(ExitCodes.SUCCESS, result.statusCode)
                assertTrue(result.output.contains("No extends directive found"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
}
