package io.karandash.cli.consumers

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.config.ConfigValidateCommand
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.interpolation.EnvInterpolation
import io.karandash.interpolation.KarandashEnvSecurity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CLI surface the consumer pipelines call is frozen: `validate` with `--verbose` and
 * `--diagnostic`, `config validate --base -f`, and six env vars that consumer configs interpolate.
 */
class ConsumerCliSurfaceTest {
    private suspend fun bootstrap(): PluginBootstrap = PluginBootstrap(envReader = { null }).also { it.loadPluginsFromDirs(emptyList()) }

    @Test
    fun `validate keeps verbose and diagnostic options`() =
        runTest {
            val bootstrap = bootstrap()
            try {
                val help = ValidateCommand(bootstrap).test(listOf("--help")).output
                listOf("--verbose", "--diagnostic").forEach { assertTrue(it in help, "$it missing from validate help") }
            } finally {
                bootstrap.shutdown()
            }
        }

    @Test
    fun `config validate keeps base and file options`() =
        runTest {
            val bootstrap = bootstrap()
            try {
                val help = ConfigValidateCommand(bootstrap).test(listOf("--help")).output
                listOf("--base", "--file", "-f").forEach { assertTrue(it in help, "$it missing from config validate help") }
            } finally {
                bootstrap.shutdown()
            }
        }

    @Test
    fun `consumer env vars still interpolate`() {
        val names = listOf(
            "KARANDASH_PROJECT_TOKEN",
            "KARANDASH_GITLAB_TOKEN",
            "GITLAB_TOKEN",
            "KARANDASH_AI_MAX_TOKENS",
            "BIFROST_API_KEY",
            "BIFROST_BASE_URL",
        )
        names.forEach { name ->
            val denied = mutableSetOf<String>()
            val result = EnvInterpolation.interpolate("\${$name}", KarandashEnvSecurity.DENYLIST, mapOf(name to "v"), deniedVars = denied)
            assertEquals(emptySet(), denied, "$name denied")
            assertTrue(result.isNotEmpty(), name)
        }
    }
}
