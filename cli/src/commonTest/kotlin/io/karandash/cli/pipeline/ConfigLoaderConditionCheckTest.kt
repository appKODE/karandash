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

/** a `when` expression is parsed at load, against the predicates of the loaded plugins. */
class ConfigLoaderConditionCheckTest {
    private suspend fun load(
        condition: String,
        strict: Boolean,
        lines: MutableList<String>,
    ) {
        val dir = TestFiles.createTestTempDirectory("condition-check")
        TestFiles.writeText(Path(dir, "config.yaml"), "policies:\n  - when: \"$condition\"\n    enabled: false\n")
        val bootstrap = PluginBootstrap(envReader = { null }).also { it.loadPluginsFromDirs(emptyList()) }
        try {
            val options = ValidationOptions.configOnly("$dir/config.yaml", bootstrap).copy(strict = strict)
            ConfigLoader(options, bootstrap, CliLogger(sink = { lines += it })).loadConfig()
        } finally {
            bootstrap.shutdown()
        }
    }

    /** An unknown predicate is a warning by default, not a silently dead policy. */
    @Test
    fun `unknown predicate warns without strict`() =
        runTest {
            val lines = mutableListOf<String>()
            load("no_such_predicate(x)", strict = false, lines)
            assertTrue(lines.any { "no_such_predicate" in it }, "$lines")
        }

    /** Under `--strict` the same expression stops the load. */
    @Test
    fun `unknown predicate fails the load under strict`() =
        runTest {
            val error = assertFailsWith<ConfigException> { load("no_such_predicate(x)", strict = true, mutableListOf()) }
            assertTrue("no_such_predicate" in error.message.orEmpty(), error.message)
        }

    /** A predicate a plugin contributes (`has_tag`) is known once the registry is populated. */
    @Test
    fun `plugin predicate has_tag loads under strict`() =
        runTest {
            load("has_tag(x)", strict = true, mutableListOf())
        }
}
