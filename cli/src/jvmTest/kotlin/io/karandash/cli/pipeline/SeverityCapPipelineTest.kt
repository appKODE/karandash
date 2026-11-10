package io.karandash.cli.pipeline

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.compat.ConsoleCapture
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shadow mode end to end: `severity-cap:` and `rules.<id>.severity-cap` from a config file decide the exit code. */
class SeverityCapPipelineTest {
    private val mr = """
        {"id": "1", "iid": 1, "title": "x", "description": "", "source_branch": "feature/a", "target_branch": "main",
         "author": {"username": "dev", "name": "Dev"}, "state": "opened", "draft": false, "labels": [], "commits": [], "changes": []}
    """.trimIndent()

    private suspend fun run(config: String): Pair<Int, List<String>> {
        val dir = TestFiles.createTestTempDirectory("severity-cap")
        val configFile = Path(dir, ".karandash", "config.yaml")
        val output = Path(dir, "out.json")
        TestFiles.writeText(configFile, config.trimIndent() + "\n")
        TestFiles.writeText(Path(dir, "mr.json"), mr)
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        val (result, _, err) = ConsoleCapture.capture {
            try {
                ValidateCommand(bootstrap).test(
                    listOf("--no-auto-detect", "--no-cache", "--config", configFile.toString(), "--mr", Path(dir, "mr.json").toString()) +
                        listOf("--format", "json", "--output", output.toString()),
                )
            } finally {
                bootstrap.shutdown()
            }
        }
        val status = result.statusCode
        assertTrue(TestFiles.exists(output), "exit $status: ${result.output}$err")
        val findings = KarandashJson.Default
            .parseToJsonElement(TestFiles.readText(output))
            .jsonObject
            .getValue("findings")
            .jsonArray
        TestFiles.deleteRecursively(dir)
        return status to findings.map {
            it.jsonObject
                .getValue("severity")
                .jsonPrimitive.content
                .lowercase()
        }
    }

    @Test
    fun `without a cap a too short title fails the run`() =
        runTest {
            val (status, severities) = run(RULES)
            assertEquals(ExitCodes.VALIDATION_FAILED, status)
            assertEquals(listOf("error"), severities)
        }

    @Test
    fun `severity-cap warning reports the error as a warning and passes`() =
        runTest {
            val (status, severities) = run("severity-cap: warning\n$RULES")
            assertEquals(ExitCodes.SUCCESS, status)
            assertEquals(listOf("warning"), severities)
        }

    @Test
    fun `a rule severity-cap wins over the top-level cap`() =
        runTest {
            val (status, severities) = run("severity-cap: info\n$RULES    severity-cap: error\n")
            assertEquals(ExitCodes.VALIDATION_FAILED, status)
            assertEquals(listOf("error"), severities)
        }

    private companion object {
        const val RULES = "rules:\n  title-format:\n    min-length: 10\n"
    }
}
