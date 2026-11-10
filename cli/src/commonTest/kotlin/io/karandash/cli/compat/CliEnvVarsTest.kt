package io.karandash.cli.compat

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.CliEnvVars
import io.karandash.cli.commands.config.ConfigValidateCommand
import io.karandash.cli.commands.validate.ValidateCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** `KARANDASH_CONFIG` stands in for `--config`/`--file`, `KARANDASH_STRICT` for `validate --strict`. */
class CliEnvVarsTest {
    @Test
    fun `KARANDASH_STRICT makes a warnings-only run exit like --strict`() =
        runTest(timeout = 5.minutes) {
            val args = listOf("--config", config("warnings-only"), "--mr", MR)
            assertEquals(0, validate(args, emptyMap()))
            assertEquals(5, validate(args, mapOf(CliEnvVars.STRICT to "true")))
            assertEquals(0, validate(args, mapOf(CliEnvVars.STRICT to "false")))
        }

    @Test
    fun `KARANDASH_CONFIG supplies the validate config and --config still wins`() =
        runTest(timeout = 5.minutes) {
            assertEquals(1, validate(listOf("--mr", MR), mapOf(CliEnvVars.CONFIG to config("failing"))))
            assertEquals(
                0,
                validate(listOf("--config", config("passing"), "--mr", MR), mapOf(CliEnvVars.CONFIG to config("failing"))),
            )
        }

    @Test
    fun `KARANDASH_CONFIG supplies the config validate file`() =
        runTest(timeout = 5.minutes) {
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPluginsFromDirs(emptyList())
            try {
                val result = ConfigValidateCommand(bootstrap).test(emptyList(), envvars = mapOf(CliEnvVars.CONFIG to config("passing")))
                assertEquals(0, result.statusCode, result.output)
                assertContains(result.stdout, "Config is valid: ${config("passing")}")
            } finally {
                bootstrap.shutdown()
            }
        }

    private suspend fun validate(
        args: List<String>,
        envvars: Map<String, String>,
    ): Int {
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        return try {
            ValidateCommand(bootstrap).test(listOf("--no-auto-detect", "--no-cache") + args, envvars = envvars).statusCode
        } finally {
            bootstrap.shutdown()
        }
    }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"

        fun config(name: String) = "${Golden.ROOT}/output/configs/$name.yaml"
    }
}
