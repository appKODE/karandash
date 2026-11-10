package io.karandash.cli.compat

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Freezes what `karandash validate` prints for a fixed MR (`tools/integration-tests/fixtures/test-mr.json`, file
 * mode, no network) in every machine-readable format plus the console; [CompatExitCodesTest] holds
 * the exit code of each scenario.
 *
 * Goldens live in `compat/output/`. Console output is ANSI-stripped; timestamps, durations, the tool version and the
 * repo path are normalized ([Golden.normalize]).
 */
class CompatOutputSnapshotTest {
    @Test
    fun `every output format keeps its rendering of a fixed MR`() =
        runTest(timeout = 5.minutes) {
            val mismatches = FORMATS.mapNotNull { format ->
                val run = validate("--config", config("failing"), "--mr", MR, "--format", format)
                Golden.check("output/format-$format.txt", "exit=${run.exit}\n${Golden.normalize(run.stdout)}")
            }
            Golden.assertAllMatch(mismatches)
        }

    private class Run(
        val exit: Int,
        val stdout: String,
    )

    private suspend fun validate(vararg args: String): Run {
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        // the stdout output module prints via println, the CLI via clikt echo: collect both
        val (result, out, _) = ConsoleCapture.capture {
            try {
                ValidateCommand(bootstrap).test(listOf("--no-auto-detect", "--no-cache") + args)
            } finally {
                bootstrap.shutdown()
            }
        }
        return Run(result.statusCode, result.stdout + out)
    }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        val FORMATS = listOf("json", "sarif", "rdjson", "junit", "markdown", "console", "html", "slack")

        fun config(name: String) = "${Golden.ROOT}/output/configs/$name.yaml"
    }
}
