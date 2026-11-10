package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.api.ModuleType
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.generate.GenerateCommand
import io.karandash.registry.PluginTemplateGenerator
import io.karandash.registry.TemplateConfig
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GenerateCommandTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("generate-command-test")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    @Test
    fun `generates rule plugin structure`() {
        val config = TemplateConfig(
            name = "test-plugin",
            packageName = "io.example.testplugin",
            type = ModuleType.RULE
        )
        val result = PluginTemplateGenerator().generateProject("test-plugin", ModuleType.RULE, config, tempDir)
        assertTrue(result.isSuccess, "generateProject should succeed: ${result.exceptionOrNull()?.message}")

        val pluginDir = Path(tempDir, "test-plugin-plugin")
        assertTrue(TestFileHelpers.exists(pluginDir))
        assertTrue(TestFileHelpers.exists(Path(pluginDir, "build.gradle.kts")))
        assertTrue(TestFileHelpers.exists(Path(pluginDir, "src/commonMain/kotlin")))
        assertTrue(TestFileHelpers.exists(Path(pluginDir, "README.md")))
    }

    @Test
    fun `generates reporter plugin structure`() {
        val config = TemplateConfig(
            name = "test-reporter",
            packageName = "io.example.testreporter",
            type = ModuleType.REPORTER
        )
        val result = PluginTemplateGenerator().generateProject("test-reporter", ModuleType.REPORTER, config, tempDir)
        assertTrue(result.isSuccess, "generateProject should succeed: ${result.exceptionOrNull()?.message}")

        val pluginDir = Path(tempDir, "test-reporter-plugin")
        assertTrue(TestFileHelpers.exists(pluginDir))
        assertTrue(TestFileHelpers.exists(Path(pluginDir, "build.gradle.kts")))
        assertTrue(TestFileHelpers.exists(Path(pluginDir, "src/commonMain/kotlin")))
        assertTrue(TestFileHelpers.exists(Path(pluginDir, "README.md")))
    }

    @Test
    fun `every documented type label scaffolds a project`() {
        for (label in listOf("rule", "reporter", "platform-service", "provider", "mutator", "comment-poster")) {
            val result = GenerateCommand(PluginBootstrap())
                .test(listOf("--type", label, "--name", "p-$label", "--output", tempDir.toString()))
            assertEquals(0, result.statusCode, "--type $label: ${result.output}")
            assertTrue(TestFileHelpers.exists(Path(tempDir, "p-$label-plugin")), "--type $label created no project")
        }
    }

    /** `generate` is a supported entry point to the same scaffold as `plugin create`. */
    @Test
    fun `generate writes the plugin create scaffold with no deprecation`() {
        val result = GenerateCommand(
            PluginBootstrap()
        ).test(listOf("--type", "rule", "--name", "same-path", "--output", Path(tempDir, "generate").toString()))
        assertEquals(0, result.statusCode, result.output)
        assertEquals("", result.stderr, result.output)
        assertFalse("deprecat" in GenerateCommand(PluginBootstrap()).test(listOf("--help")).output.lowercase())
        assertTrue("Created rule plugin: same-path-plugin/" in result.output, result.output)

        val createConfig = TemplateConfig(name = "same-path", packageName = "io.example.samepath", type = ModuleType.RULE)
        val createDir = Path(tempDir, "create")
        PluginTemplateGenerator().generateProject("same-path", ModuleType.RULE, createConfig, createDir).getOrThrow()
        val generated = tree(Path(tempDir, "generate"))
        assertTrue(generated.isNotEmpty())
        assertEquals(tree(createDir), generated)

        val trailing = GenerateCommand(
            PluginBootstrap()
        ).test(listOf("--type", "rule", "--name", "trailing-", "--output", tempDir.toString()))
        assertEquals(1, trailing.statusCode, trailing.output)
        assertTrue("lowercase alphanumeric" in trailing.stderr, trailing.output)
    }

    /** Every file under [root] by relative path, with its text. */
    private fun tree(root: Path): Map<String, String> {
        val files = mutableMapOf<String, String>()

        fun walk(dir: Path) {
            for (child in SystemFileSystem.list(dir)) {
                if (SystemFileSystem.metadataOrNull(child)?.isDirectory == true) {
                    walk(child)
                } else {
                    files[child.toString().removePrefix(root.toString())] = TestFileHelpers.readFile(child)
                }
            }
        }
        walk(root)
        return files
    }

    @Test
    fun `invalid input exits non-zero`() {
        val badName = GenerateCommand(PluginBootstrap())
            .test(listOf("--type", "rule", "--name", "BadName", "--output", tempDir.toString()))
        assertEquals(1, badName.statusCode, badName.output)
        assertEquals(1, GenerateCommand(PluginBootstrap()).test(listOf("--name", "ok")).statusCode)
    }
}
