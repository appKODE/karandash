package io.karandash.cli.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A config section that does not fit its module's schema is a warning, and an error under `--strict`. */
class ConfigLoaderSchemaWarningTest {
    private val config = "rules:\n  title-format: 42\n"

    private suspend fun load(
        strict: Boolean,
        lines: MutableList<String>,
    ) {
        val dir = TestFiles.createTestTempDirectory("schema-warning")
        TestFiles.writeText(Path(dir, "config.yaml"), config)
        val bootstrap = PluginBootstrap(envReader = { null }).also { it.loadPluginsFromDirs(emptyList()) }
        try {
            val options = ValidationOptions.configOnly("$dir/config.yaml", bootstrap).copy(strict = strict)
            ConfigLoader(options, bootstrap, CliLogger(sink = { lines += it })).loadConfig()
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `a schema mismatch is logged as a warning`() =
        runTest {
            val lines = mutableListOf<String>()
            load(strict = false, lines)
            assertTrue(lines.any { "titleFormat" in it && "Expected object config" in it && "WARN" in it.uppercase() }, "$lines")
        }

    @Test
    fun `a schema mismatch fails the load under strict`() =
        runTest {
            val error = assertFailsWith<ConfigException> { load(strict = true, mutableListOf()) }
            assertTrue("titleFormat" in error.message.orEmpty(), error.message)
        }
}
