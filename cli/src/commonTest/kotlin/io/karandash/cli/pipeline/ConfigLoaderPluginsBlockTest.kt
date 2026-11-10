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

/** Without a `manifest.yaml`, the config file's own `plugins:` block drives missing-plugin detection. */
class ConfigLoaderPluginsBlockTest {
    private suspend fun load(
        yaml: String,
        manifest: String? = null,
    ) {
        val dir = TestFiles.createTestTempDirectory("plugins-block")
        TestFiles.writeText(Path(dir, "config.yaml"), yaml)
        manifest?.let { TestFiles.writeText(Path(dir, "manifest.yaml"), it) }
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        try {
            val options = ValidationOptions(
                configPathStr = "$dir/config.yaml",
                mrDataStr = null,
                mrId = null,
                platform = null,
                project = null,
                prUrl = null,
                format = "json",
                outputPathStr = null,
                quiet = false,
                verbose = false,
                strict = false,
                dryRun = false,
                explain = false,
                fix = false,
                yes = false,
                noAutoDetect = true,
                outputWriter = {},
                sharedBootstrap = bootstrap,
            )
            ConfigLoader(options, bootstrap, CliLogger(sink = {})).loadConfig()
        } finally {
            bootstrap.shutdown()
        }
    }

    private val config = "plugins:\n  on-missing: fail\n  install:\n    - acme-rule\nrules:\n  acme-rule: {}\n"

    @Test
    fun `config plugins block on-missing fail rejects an uninstalled declared plugin`() =
        runTest {
            val error = assertFailsWith<ConfigException> { load(config) }
            assertTrue("acme-rule" in error.message.orEmpty(), error.message)
            assertTrue("declared in manifest" in error.message.orEmpty(), error.message)
        }

    @Test
    fun `manifest yaml takes precedence over the config plugins block`() =
        runTest {
            load(config, manifest = "plugins:\n  on-missing: warn\n")
        }
}
