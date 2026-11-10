package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.testing.test
import io.karandash.ai.pipeline.AiTrace
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** `--trace` turns on [AiTrace] for the run, the same as `KARANDASH_AI_TRACE=1`. */
class AiTraceFlagTest {
    private val dir = TestFiles.createTestTempDirectory("karandash-trace-flag")

    @Test
    fun `validate-local --trace enables AiTrace`() =
        runTest {
            val config = Path(dir, "config.json").also { TestFiles.writeText(it, """{"rules":{"sizeLimits":{"maxLines":100000}}}""") }
            try {
                ValidateLocalCommand(PluginBootstrap().apply { loadPlugins() })
                    .test("--config $config --target HEAD --trace --dry-run")

                assertTrue(AiTrace.enabled)
            } finally {
                AiTrace.enable(false)
            }
        }
}
