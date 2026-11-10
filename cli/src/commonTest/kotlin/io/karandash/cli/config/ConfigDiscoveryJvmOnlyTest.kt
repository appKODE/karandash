package io.karandash.cli.config

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.config.pipeline.ConfigDiscovery
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class ConfigDiscoveryJvmOnlyTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { dir -> TestFileHelpers.deleteTempDir(dir) }
        tempDirs.clear()
    }

    private fun createTempDir(): Path {
        val dir = TestFileHelpers.createTempDir("config-discovery-test")
        tempDirs.add(dir)
        return dir
    }

    @Test
    fun `discovers readable config file in directory`() =
        runTest {
            val tempDir = createTempDir()
            val configDir = Path(tempDir, ".karandash")
            TestFileHelpers.writeFile(
                Path(configDir, "config.yaml").also {
                    // createDirectories before writing
                    kotlinx.io.files.SystemFileSystem
                        .createDirectories(configDir)
                },
                "rules:\n  title-format:\n    pattern: \"^feat:\"\n"
            )

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNotNull(result.file, "Should discover the config.yaml file")
        }
}
