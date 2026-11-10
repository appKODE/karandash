package io.karandash.cli.compat

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** Freezes the exit code of each `karandash validate` scenario (`compat/output/exit-codes.txt`) on every target. */
class CompatExitCodesTest {
    @Test
    fun `exit codes per scenario stay the same`() =
        runTest(timeout = 5.minutes) {
            val table = EXIT_SCENARIOS.map { (name, args) ->
                "$name: ${validate(*args.toTypedArray())}"
            }
            Golden.assertAllMatch(listOfNotNull(Golden.check("output/exit-codes.txt", table.joinToString("\n"))))
        }

    private suspend fun validate(vararg args: String): Int {
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        return try {
            ValidateCommand(bootstrap).test(listOf("--no-auto-detect", "--no-cache") + args).statusCode
        } finally {
            bootstrap.shutdown()
        }
    }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"

        fun config(name: String) = "${Golden.ROOT}/output/configs/$name.yaml"

        val EXIT_SCENARIOS: List<Pair<String, List<String>>> = listOf(
            "passing" to listOf("--config", config("passing"), "--mr", MR),
            "failing" to listOf("--config", config("failing"), "--mr", MR),
            "warnings-only" to listOf("--config", config("warnings-only"), "--mr", MR),
            "warnings-only --strict" to listOf("--config", config("warnings-only"), "--mr", MR, "--strict"),
            "policy severity: warning" to listOf("--config", config("policy-severity"), "--mr", MR),
            "format rdjsonl (listed in --help)" to listOf("--config", config("failing"), "--mr", MR, "--format", "rdjsonl"),
            "malformed config" to listOf("--config", config("malformed"), "--mr", MR),
            "missing config file" to listOf("--config", config("does-not-exist"), "--mr", MR),
            "missing MR file" to listOf("--config", config("passing"), "--mr", "does-not-exist.json"),
            "unknown format" to listOf("--config", config("failing"), "--mr", MR, "--format", "no-such-format"),
            "dry-run" to listOf("--config", config("failing"), "--mr", MR, "--dry-run"),
        )
    }
}
