@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.exception.ContentResolveException
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.sdk.content.ContentSource
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Plugin contexts of a loaded run resolve file content against the config dir, confined to it. */
class ConfigLoaderResolveContentTest {
    @Test
    fun `run contexts resolve local files under the config dir and refuse escapes`() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("resolve-content")
            TestFiles.writeText(Path(dir, "config.yaml"), "rules: {}\n")
            TestFiles.writeText(Path(dir, "prompt.md"), "be terse")
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPluginsFromDirs(emptyList())
            try {
                val stage = ConfigLoader(options(bootstrap, "$dir/config.yaml"), bootstrap, CliLogger(sink = {})).loadConfig()
                val ctx = stage.contexts.forModule("some-plugin")

                assertEquals("be terse", ctx.resolveContent(ContentSource.LocalFile("prompt.md")))
                val escape = assertFailsWith<ContentResolveException> {
                    ctx.resolveContent(ContentSource.LocalFile("../outside.md"))
                }
                assertTrue("outside the config directory" in escape.message.orEmpty(), escape.message)
            } finally {
                bootstrap.shutdown()
            }
        }

    private fun options(
        bootstrap: PluginBootstrap,
        config: String
    ) = ValidationOptions(
        configPathStr = config,
        mrDataStr = null,
        mrId = null,
        platform = null,
        project = null,
        prUrl = null,
        format = "json",
        outputPathStr = null,
        quiet = true,
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
}
