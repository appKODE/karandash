package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.explain.explainCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.platform.readText
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `explain` warns and falls back to zero-config defaults on a bad config (exit 0), and `--output` writes a file. */
class ExplainCommandTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-explain")
    private val bootstrap = PluginBootstrap(envReader = { null })

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private fun file(content: String) = Path(tempDir, "config.yaml").also { TestFileHelpers.writeFile(it, content) }.toString()

    private suspend fun explain(vararg args: String) =
        bootstrap.loadPluginsFromDirs(emptyList()).let { explainCommand(bootstrap).test(args.toList()) }

    @Test
    fun `bad config warns and still explains`() =
        runTest {
            val path = file("rules: [unclosed\n")
            val result = explain("--config", path)
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertContains(result.stderr, "Warning: Failed to parse config:")
            assertContains(result.stdout, "Karandash Config Explanation")
            assertContains(result.stdout, "Config: $path")
        }

    @Test
    fun `a missing plugin under on-missing fail is an error not a zero-config explanation`() =
        runTest {
            val result = explain("--config", file("plugins:\n  on-missing: fail\nrules:\n  no-such-module-xyz: {}\n"))

            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
            assertContains(result.stderr, "noSuchModuleXyz")
            assertFalse(result.stdout.contains("Karandash Config Explanation"), result.stdout)
        }

    @Test
    fun `output option writes the explanation to a file`() =
        runTest {
            val out = Path(tempDir, "explain.txt")
            val result = explain("--config", file("rules: {}\n"), "--output", out.toString())
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertContains(result.stdout, "Config explanation written to $out")
            assertContains(out.readText(), "Karandash Config Explanation")
        }

    @Test
    fun `a list of section objects is explained as an object list`() =
        runTest {
            val config =
                "rules:\n  descriptionFormat:\n    mrSections:\n" +
                    "      - name: TASK\n        pattern: \"TASK\"\n        required: true\n"
            val result = explain("--config", file(config))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertContains(result.stdout, "[v] mrSections: [{name: \"TASK\", pattern: \"TASK\", required: true}]  (object list)")
            assertFalse("Expected all list elements to be strings" in result.stdout, result.stdout)
        }

    @Test
    fun `explain resolves extends chain like validate`() =
        runTest {
            TestFileHelpers.writeFile(
                Path(tempDir, "base.yaml"),
                "env:\n  TITLE_PATTERN: \"^feat\"\nrules:\n  title-format:\n    pattern: \"${'$'}{TITLE_PATTERN}\"\n",
            )
            val result = explain("--config", file("extends: \"base.yaml\"\n"))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertContains(result.stdout, "pattern: \"^feat\"")
        }
}
