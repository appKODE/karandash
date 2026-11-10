package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.validate.ValidateExtendsCommand
import io.karandash.cli.errors.ExitCodes
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test suite for ValidateExtendsCommand.
 *
 * Coverage:
 * - Command produces correct output
 * - Parallel URL checking
 * - Cycle detection
 * - Depth violations
 * - Exit codes (0 = success, 1 = validation failed, 2 = config error)
 */
class ValidateExtendsCommandTest {
    private lateinit var bootstrap: PluginBootstrap

    private suspend fun getBootstrap(): PluginBootstrap {
        if (!::bootstrap.isInitialized) {
            bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
        }
        return bootstrap
    }

    @Test
    fun `validate-extends with no extends directive shows message`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-none")
            try {
                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(
                    configFile,
                    """
                rules:
                  title-format:
                    pattern: "^feat:.*"
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap)
                val result = command.test("--config $configFile")

                assertEquals(ExitCodes.SUCCESS, result.statusCode)
                assertTrue(result.output.contains("No extends directive found"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends with local file extends shows OK`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-local")
            try {
                val parentFile = Path(testDir, "parent.yaml")
                TestFileHelpers.writeFile(
                    parentFile,
                    """
                rules:
                  title-format:
                    pattern: "^parent:"
                    """.trimIndent()
                )

                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(
                    configFile,
                    """
                extends: "parent.yaml"
                rules:
                  title-format:
                    pattern: "^child:"
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap)
                val result = command.test("--config $configFile")

                assertEquals(ExitCodes.SUCCESS, result.statusCode)
                assertTrue(result.output.contains("[OK]"))
                assertTrue(result.output.contains("parent.yaml"))
                assertTrue(result.output.contains("local file"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends detects circular references`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-circular")
            try {
                // Create a circular reference: a.yaml -> b.yaml -> a.yaml
                val aFile = Path(testDir, "a.yaml")
                val bFile = Path(testDir, "b.yaml")

                TestFileHelpers.writeFile(
                    aFile,
                    """
                extends: "b.yaml"
                rules:
                  title-format:
                    pattern: "^a:"
                    """.trimIndent()
                )

                TestFileHelpers.writeFile(
                    bFile,
                    """
                extends: "a.yaml"
                rules:
                  title-format:
                    pattern: "^b:"
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap)
                val result = command.test("--config $aFile")

                assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode)
                assertTrue(result.output.contains("Circular"))
                assertTrue(result.output.contains("E0010"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends detects depth violations`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-depth")
            try {
                // Create a chain longer than MAX_DEPTH (5)
                var currentContent: String

                for (i in 5 downTo 1) {
                    val nextFile = if (i == 5) "level6.yaml" else "level${i + 1}.yaml"
                    currentContent = """
                    extends: "$nextFile"
                    rules:
                      title-format:
                        pattern: "^level$i:"
                    """.trimIndent()

                    val file = Path(testDir, "level${i + 1}.yaml")
                    TestFileHelpers.writeFile(file, currentContent)
                }

                val rootFile = Path(testDir, "level1.yaml")
                TestFileHelpers.writeFile(
                    rootFile,
                    """
                extends: "level2.yaml"
                rules:
                  title-format:
                    pattern: "^level1:"
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap)
                val result = command.test("--config $rootFile")

                assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode)
                assertTrue(result.output.contains("depth") || result.output.contains("E0011"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends with verbose flag shows detailed output`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-verbose")
            try {
                val parentFile = Path(testDir, "parent.yaml")
                TestFileHelpers.writeFile(
                    parentFile,
                    """
                rules:
                  title-format:
                    pattern: "^parent:"
                    """.trimIndent()
                )

                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(
                    configFile,
                    """
                extends: "parent.yaml"
                rules:
                  title-format:
                    pattern: "^child:"
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap)
                val result = command.test("--config $configFile --verbose")

                assertEquals(ExitCodes.SUCCESS, result.statusCode)
                // "Using config file:" is now logged at debug level to stderr;
                // verify the command succeeds and produces structured output
                assertTrue(result.output.contains("Extends Chain Validation"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends with multiple extends shows all URLs`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-multiple")
            try {
                val parent1 = Path(testDir, "parent1.yaml")
                val parent2 = Path(testDir, "parent2.yaml")

                TestFileHelpers.writeFile(
                    parent1,
                    """
                rules:
                  title-format:
                    pattern: "^p1:"
                    """.trimIndent()
                )

                TestFileHelpers.writeFile(
                    parent2,
                    """
                rules:
                  title-format:
                    pattern: "^p2:"
                    """.trimIndent()
                )

                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(
                    configFile,
                    """
                extends:
                  - "parent1.yaml"
                  - "parent2.yaml"
                rules:
                  title-format:
                    pattern: "^child:"
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap)
                val result = command.test("--config $configFile")

                assertEquals(ExitCodes.SUCCESS, result.statusCode)
                assertTrue(result.output.contains("parent1.yaml"))
                assertTrue(result.output.contains("parent2.yaml"))
                assertTrue(result.output.contains("Total extends: 2"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends with missing config file returns config error`() =
        runTest {
            val bootstrap = getBootstrap()
            val command = ValidateExtendsCommand(bootstrap)
            val result = command.test("--config /nonexistent/config.yaml")

            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        }

    @Test
    fun `validate-extends shows validation summary`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-summary")
            try {
                val parentFile = Path(testDir, "parent.yaml")
                TestFileHelpers.writeFile(
                    parentFile,
                    """
                rules:
                  title-format:
                    pattern: "^parent:"
                    """.trimIndent()
                )

                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(
                    configFile,
                    """
                extends: "parent.yaml"
                rules:
                  title-format:
                    pattern: "^child:"
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap)
                val result = command.test("--config $configFile")

                assertEquals(ExitCodes.SUCCESS, result.statusCode)
                assertTrue(result.output.contains("Extends Chain Validation"))
                assertTrue(result.output.contains("Resolution:"))
                assertTrue(result.output.contains("Total depth:"))
                assertTrue(result.output.contains("Total extends:"))
                assertTrue(result.output.contains("Circular references: none"))
                assertTrue(result.output.contains("Result:"))
                assertTrue(result.output.contains("0 error(s)"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends with invalid config returns config error`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-invalid")
            try {
                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(
                    configFile,
                    """
                invalid: yaml: [syntax
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap)
                val result = command.test("--config $configFile")

                assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends with diamond inheritance does not report false circular`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-diamond")
            try {
                // Diamond: child -> [a, b] both -> grandparent
                val grandparent = Path(testDir, "grandparent.yaml")
                TestFileHelpers.writeFile(
                    grandparent,
                    """
                rules:
                  title-format:
                    pattern: "^gp:"
                    """.trimIndent()
                )

                val aFile = Path(testDir, "a.yaml")
                TestFileHelpers.writeFile(
                    aFile,
                    """
                extends: "grandparent.yaml"
                rules:
                  title-format:
                    pattern: "^a:"
                    """.trimIndent()
                )

                val bFile = Path(testDir, "b.yaml")
                TestFileHelpers.writeFile(
                    bFile,
                    """
                extends: "grandparent.yaml"
                rules:
                  title-format:
                    pattern: "^b:"
                    """.trimIndent()
                )

                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(
                    configFile,
                    """
                extends:
                  - "a.yaml"
                  - "b.yaml"
                rules:
                  title-format:
                    pattern: "^child:"
                    """.trimIndent()
                )

                val command = ValidateExtendsCommand(bootstrap)
                val result = command.test("--config $configFile")

                // Should NOT report circular reference
                assertEquals(ExitCodes.SUCCESS, result.statusCode)
                assertTrue(result.output.contains("Circular references: none"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends maps an unexpected exception to exit code 4`() =
        runTest {
            val testDir = TestFileHelpers.createTempDir("validate-extends-internal")
            try {
                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(configFile, "rules: {}\n")

                // loadPlugins() is never called, so formatRegistry throws IllegalStateException (not a ConfigException)
                val result = ValidateExtendsCommand(PluginBootstrap()).test("--config $configFile")

                assertEquals(ExitCodes.INTERNAL_ERROR, result.statusCode, result.output)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends merges the chain and reports a parent whose file reference is missing`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-file-ref")
            try {
                TestFileHelpers.writeFile(Path(testDir, "parent.yaml"), "rules:\n  title-format:\n    pattern:\n      file: missing.txt\n")
                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(configFile, "extends: \"parent.yaml\"\n")

                val result = ValidateExtendsCommand(bootstrap).test("--config $configFile")

                assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode, result.output)
                assertTrue(result.output.contains("[FAIL]"), result.output)
                assertTrue(result.output.contains("missing.txt"), result.output)
                assertTrue(result.output.contains("Result: 1 error(s)"), result.output)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    /** A format plugin whose parser rejects the file with the user-facing [ConfigParseException]. */
    private val rejectingFormat = object : ConfigFormatModule {
        override val formatId = "broken"
        override val fileExtensions = setOf("broken")

        override suspend fun parseToJsonElement(content: String): JsonElement = throw ConfigParseException("line 1: unexpected token")
    }

    private suspend fun rejectingBootstrap() =
        PluginBootstrap(onFormatRegistryCreated = { it.register(rejectingFormat) }).also { it.loadPluginsFromDirs(emptyList()) }

    @Test
    fun `validate-extends reports a root its parser rejects as a config error`() =
        runTest {
            val testDir = TestFileHelpers.createTempDir("validate-extends-bad-root")
            try {
                val configFile = Path(testDir, "config.broken")
                TestFileHelpers.writeFile(configFile, "rules {\n")

                val result = ValidateExtendsCommand(rejectingBootstrap()).test("--config $configFile")

                // the message goes to the CLI logger (process stderr), not the clikt test output
                assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
                assertFalse(result.output.contains("Internal error"), result.output)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends reports a parent its parser rejects as a failed merge`() =
        runTest {
            val testDir = TestFileHelpers.createTempDir("validate-extends-bad-parent")
            try {
                TestFileHelpers.writeFile(Path(testDir, "parent.broken"), "rules {\n")
                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(configFile, "extends: \"parent.broken\"\n")

                val result = ValidateExtendsCommand(rejectingBootstrap()).test("--config $configFile")

                assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode, result.output)
                assertTrue(result.output.contains("[FAIL]"), result.output)
                assertFalse(result.output.contains("Internal error"), result.output)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `validate-extends reports a chain size violation with its own error code`() =
        runTest {
            val bootstrap = getBootstrap()
            val testDir = TestFileHelpers.createTempDir("validate-extends-chain-size")
            try {
                TestFileHelpers.writeFile(Path(testDir, "a.yaml"), "rules: {}\n")
                TestFileHelpers.writeFile(Path(testDir, "b.yaml"), "rules: {}\n")
                val configFile = Path(testDir, ".karandash.yaml")
                TestFileHelpers.writeFile(configFile, "limits:\n  extends:\n    max-chain-size: 2\nextends: [\"a.yaml\", \"b.yaml\"]\n")

                val result = ValidateExtendsCommand(bootstrap).test("--config $configFile")

                assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode, result.output)
                assertTrue(result.output.contains("Chain size violation: DETECTED"), result.output)
                assertTrue(result.output.contains("E0024"), result.output)
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
}
