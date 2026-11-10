package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginCommandTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("karandash-plugin-test")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    @Test
    fun `list command shows no plugins for empty directory`() {
        val command = pluginCommand()
        val result = command.test(listOf("list", "--plugin-dir", tempDir.toString()))

        assertTrue(
            result.output.contains("No plugins installed") ||
                result.output.contains("No plugins"),
            "Expected 'no plugins' message but got: ${result.output}"
        )
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `list command shows no plugins for non-existent directory`() {
        val nonExistent = Path(tempDir, "no-such-dir")
        val command = pluginCommand()
        val result = command.test(listOf("list", "--plugin-dir", nonExistent.toString()))

        assertTrue(
            result.output.contains("does not exist") ||
                result.output.contains("No plugins"),
            "Expected 'does not exist' message but got: ${result.output}"
        )
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `install command rejects non-existent file`() {
        val command = pluginCommand()
        val result = command.test(listOf("install", "/nonexistent/path/plugin.jar"))

        assertEquals(2, result.statusCode)
    }

    @Test
    fun `install command rejects non-jar file`() {
        val notJar = Path(tempDir, "plugin.txt")
        TestFileHelpers.writeFile(notJar, "not a jar")

        val command = pluginCommand()
        val result = command.test(listOf("install", notJar.toString()))

        assertEquals(2, result.statusCode)
    }

    @Test
    fun `remove command fails for non-existent plugin`() {
        val command = pluginCommand()
        val result = command.test(
            listOf(
                "remove",
                "nonexistent.plugin",
                "--plugin-dir",
                tempDir.toString()
            )
        )

        assertEquals(2, result.statusCode)
    }

    @Test
    fun `remove command fails for non-existent plugin directory`() {
        val nonExistent = Path(tempDir, "no-such-dir")
        val command = pluginCommand()
        val result = command.test(
            listOf(
                "remove",
                "some.plugin",
                "--plugin-dir",
                nonExistent.toString()
            )
        )

        assertEquals(2, result.statusCode)
    }

    @Test
    fun `cache clear command runs successfully on empty cache dir`() {
        val command = pluginCommand()
        val result = command.test(listOf("cache", "clear"))

        assertEquals(0, result.statusCode)
        assertTrue(
            result.output.contains("empty") || result.output.contains("Cleared"),
            "Expected cache status message but got: ${result.output}"
        )
    }
}
