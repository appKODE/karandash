package io.karandash.cli.pipeline

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.compat.ConsoleCapture
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ai.enabled: false` turns the AI subsystem off: no AI warnings, no AI state, and repeated runs give the same findings. */
class AiDisabledRunTest {
    private val mr = """
        {"id": "123", "iid": 456, "title": "x", "description": "", "source_branch": "feature/a", "target_branch": "main",
         "author": {"username": "dev", "name": "Dev"}, "state": "opened", "draft": false, "labels": [], "commits": [],
         "changes": [{"path": "src/A.kt", "changeType": "MODIFIED", "additions": 3, "deletions": 1}]}
    """.trimIndent()

    @Test
    fun `ai enabled false writes no AI state and two identical runs report the same findings`() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("ai-disabled")
            val cacheDir = Path(dir, "cache")
            val configFile = Path(dir, ".karandash", "config.yaml")
            TestFiles.writeText(Path(dir, "mr.json"), mr)
            TestFiles.writeText(
                configFile,
                "ai:\n  enabled: false\ncache:\n  dir: \"$cacheDir\"\nrules:\n  title-format:\n    pattern: \"^feat:\"\n",
            )
            try {
                val first = run(dir, configFile)
                val second = run(dir, configFile)
                assertEquals(first.first, second.first, "second run differs: ${second.second}")
                assertFalse("ai-incremental" in second.first, second.first)
                for (log in listOf(first.second, second.second)) {
                    assertFalse("AI module skipped" in log, log)
                    assertFalse("mr-review-instructions" in log, log)
                    assertFalse("incremental cache for" in log, log)
                }
                val state = Path(cacheDir, "index", "state")
                val aiState = if (SystemFileSystem.exists(state)) SystemFileSystem.list(state).map { it.name } else emptyList()
                assertTrue(aiState.none { it.startsWith("ai") }, "AI state written: $aiState")
            } finally {
                TestFiles.deleteRecursively(dir)
            }
        }

    /** Findings JSON and the run's log output. */
    private suspend fun run(
        dir: Path,
        configFile: Path,
    ): Pair<String, String> {
        val output = Path(dir, "out.json")
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        val (result, out, err) = ConsoleCapture.capture {
            try {
                ValidateCommand(bootstrap).test(
                    listOf("--no-auto-detect", "--config", configFile.toString(), "--mr", Path(dir, "mr.json").toString()) +
                        listOf("--format", "json", "--output", output.toString()),
                )
            } finally {
                bootstrap.shutdown()
            }
        }
        val log = result.output + result.stderr + out + err
        assertTrue(TestFiles.exists(output), "exit ${result.statusCode}: $log")
        val findings = TestFiles.readText(output).substringAfter("\"findings\"").substringBefore("\"summary\"")
        return findings to log
    }
}
