package io.karandash.cli.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.MissingPluginException
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** an unknown config key is a warning, and an error under `--strict`. */
class ConfigLoaderUnknownKeysStrictTest {
    private suspend fun load(
        config: String,
        strict: Boolean,
        lines: MutableList<String>,
    ) {
        val dir = TestFiles.createTestTempDirectory("unknown-keys-strict")
        TestFiles.writeText(Path(dir, "config.yaml"), config)
        val bootstrap = PluginBootstrap(envReader = { null }).also { it.loadPluginsFromDirs(emptyList()) }
        try {
            val options = ValidationOptions.configOnly("$dir/config.yaml", bootstrap).copy(strict = strict)
            ConfigLoader(options, bootstrap, CliLogger(sink = { lines += it })).loadConfig()
        } finally {
            bootstrap.shutdown()
        }
    }

    /** Without `--strict` the run goes on and the key is only warned about. */
    @Test
    fun `unknown top level key warns without strict`() =
        runTest {
            val lines = mutableListOf<String>()
            load("bogusSetting: 1\n", strict = false, lines)
            assertTrue(lines.any { "bogusSetting" in it }, "$lines")
        }

    /** Under `--strict` the same key stops the load. */
    @Test
    fun `unknown top level key fails the load under strict`() =
        runTest {
            val error = assertFailsWith<ConfigException> { load("bogusSetting: 1\n", strict = true, mutableListOf()) }
            assertTrue("bogusSetting" in error.message.orEmpty(), error.message)
        }

    /** A config with no unknown key still loads under `--strict`. */
    @Test
    fun `clean config loads under strict`() =
        runTest {
            load("dryRun: true\n", strict = true, mutableListOf())
        }

    /** A misspelled rule name is an unknown key too, so `--strict` stops the load. */
    @Test
    fun `misspelled rule name fails the load under strict`() =
        runTest {
            val config = "rules:\n  titel-format:\n    enabled: true\n"
            val error = assertFailsWith<MissingPluginException> { load(config, strict = true, mutableListOf()) }
            assertTrue("titel" in error.message.orEmpty(), error.message)
        }

    /** Without `--strict` a misspelled rule name only warns. */
    @Test
    fun `misspelled rule name warns without strict`() =
        runTest {
            val lines = mutableListOf<String>()
            load("rules:\n  titel-format:\n    enabled: true\n", strict = false, lines)
            assertTrue(lines.any { "titel" in it }, "$lines")
        }
}
