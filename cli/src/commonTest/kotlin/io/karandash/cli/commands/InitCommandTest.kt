package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.init.InitCommand
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import io.karandash.parser.KarandashParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitCommandTest {
    private lateinit var tempDir: Path
    private lateinit var bootstrap: PluginBootstrap

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("karandash-init-test")
        bootstrap = PluginBootstrap(
            onFormatRegistryCreated = ::registerBuiltInRenderers,
        )
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
        runBlocking { bootstrap.shutdown() }
    }

    @Test
    fun `creates minimal config at default path`() =
        runTest {
            bootstrap.loadPlugins()
            val outputPath = Path(tempDir, ".karandash/config.kts")
            val command = InitCommand(bootstrap)
            val result = command.test(listOf("--output", outputPath.toString(), "--format", "kts"))

            assertTrue(result.output.contains("Created minimal configuration"), "Output should contain success message")
            assertEquals(0, result.statusCode)
            assertTrue(TestFileHelpers.exists(outputPath), "Config file should exist")
            assertTrue(TestFileHelpers.readFile(outputPath).isNotEmpty(), "Config file should not be empty")
        }

    @Test
    fun `does not overwrite existing file`() =
        runTest {
            bootstrap.loadPlugins()
            val outputPath = Path(tempDir, "existing-config.kts")
            TestFileHelpers.writeFile(outputPath, "existing content")

            val command = InitCommand(bootstrap)
            val result = command.test(listOf("--output", outputPath.toString(), "--format", "kts"))

            assertTrue(result.output.contains("Config file already exists at:"))
            assertEquals(0, result.statusCode)
            assertEquals("existing content", TestFileHelpers.readFile(outputPath))
        }

    @Test
    fun `generates parseable config`() =
        runTest {
            bootstrap.loadPlugins()
            val outputPath = Path(tempDir, "minimal.kts")
            val command = InitCommand(bootstrap)
            val result = command.test(listOf("--output", outputPath.toString(), "--format", "kts"))

            assertTrue(result.output.contains("Created minimal configuration"))
            assertEquals(0, result.statusCode)

            val content = TestFileHelpers.readFile(outputPath)
            assertTrue(KarandashParser().parseToJson(content).isNotEmpty(), content)
        }

    @Test
    fun `custom output path works`() =
        runTest {
            bootstrap.loadPlugins()
            val customPath = Path(tempDir, "custom/path/my-config.kts")
            val command = InitCommand(bootstrap)
            val result = command.test(listOf("--output", customPath.toString(), "--format", "kts"))

            assertTrue(result.output.contains("Created minimal configuration"))
            assertEquals(0, result.statusCode)
            assertTrue(TestFileHelpers.exists(customPath))
            assertTrue(TestFileHelpers.exists(Path(tempDir, "custom/path")), "Parent directories should be created")
        }

    @Test
    fun `unknown format is a usage error and writes nothing`() =
        runTest {
            bootstrap.loadPlugins()
            val outputPath = Path(tempDir, "config.xml")
            val result = InitCommand(bootstrap).test(listOf("--output", outputPath.toString(), "--format", "xml"))

            assertEquals(1, result.statusCode, result.output)
            assertTrue(result.stderr.contains("--format"), result.output)
            assertTrue(!TestFileHelpers.exists(outputPath))
        }

    @Test
    fun `preset and platform compose a config that declares the schema version`() =
        runTest {
            bootstrap.loadPlugins()
            val outputPath = Path(tempDir, "strict.yaml")
            val result =
                InitCommand(bootstrap).test(
                    listOf("--output", outputPath.toString(), "--format", "yaml", "--preset", "strict", "--platform", "github"),
                )

            assertEquals(0, result.statusCode, result.output)
            assertTrue(result.output.contains("Created strict configuration"), result.output)
            val content = TestFileHelpers.readFile(outputPath)
            assertTrue(Regex("(?m)^version: \"?1\\.0\"?$").containsMatchIn(content), content)
            assertTrue(content.contains("rules:"), content)
        }

    @Test
    fun `unknown preset is a usage error and writes nothing`() =
        runTest {
            bootstrap.loadPlugins()
            val outputPath = Path(tempDir, "never.yaml")
            val result = InitCommand(bootstrap).test(listOf("--output", outputPath.toString(), "--preset", "nope"))

            assertEquals(1, result.statusCode, result.output)
            assertTrue(!TestFileHelpers.exists(outputPath))
        }

    @Test
    fun `the profile option composes the preset and platform instead of a stub`() =
        runTest {
            bootstrap.loadPlugins()
            val outputPath = Path(tempDir, "profiled.yaml")
            val result =
                InitCommand(bootstrap).test(
                    listOf("--output", outputPath.toString(), "--profile", "conventional-commits", "--platform", "github"),
                )

            assertEquals(0, result.statusCode, result.output)
            val content = TestFileHelpers.readFile(outputPath)
            assertTrue(Regex("(?m)^profile: .?conventional-commits").containsMatchIn(content), content)
            assertTrue(content.contains("platforms:") && content.contains("rules:"), content)
        }

    @Test
    fun `json init writes the schema reference and the schema file`() =
        runTest {
            bootstrap.loadPlugins()
            val outputPath = Path(tempDir, "config.json")
            val result = InitCommand(bootstrap).test(listOf("--output", outputPath.toString(), "-f", "json"))

            assertEquals(0, result.statusCode, result.output)
            val content = TestFileHelpers.readFile(outputPath)
            assertTrue(content.contains("\"\$schema\": \".karandash.schema.json\""), content)
            assertTrue(TestFileHelpers.exists(Path(tempDir, ".karandash.schema.json")))
        }

    @Test
    fun `kts init has no schema reference and writes no schema file`() =
        runTest {
            bootstrap.loadPlugins()
            val outputPath = Path(tempDir, "config.kts")
            val result = InitCommand(bootstrap).test(listOf("--output", outputPath.toString(), "-f", "kts"))

            assertEquals(0, result.statusCode, result.output)
            assertTrue(!TestFileHelpers.readFile(outputPath).contains("schema"))
            assertTrue(!TestFileHelpers.exists(Path(tempDir, ".karandash.schema.json")))
        }

    @Test
    fun `a schema file that cannot be written warns and keeps the config`() =
        runTest {
            bootstrap.loadPlugins()
            SystemFileSystem.createDirectories(Path(tempDir, ".karandash.schema.json"))
            val outputPath = Path(tempDir, "warned.yaml")
            val result = InitCommand(bootstrap).test(listOf("--output", outputPath.toString(), "-f", "yaml"))

            assertEquals(0, result.statusCode, result.output + result.stderr)
            assertTrue(result.stderr.contains("Warning: could not write .karandash.schema.json"), result.stderr)
            assertTrue(TestFileHelpers.exists(outputPath))
        }
}
