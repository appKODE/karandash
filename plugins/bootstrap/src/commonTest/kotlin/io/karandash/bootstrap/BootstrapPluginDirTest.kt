package io.karandash.bootstrap

import io.karandash.loader.PluginDiscovery
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** The default plugin directory scanned by [PluginBootstrap.loadPlugins] is `KARANDASH_PLUGIN_DIR` when set. */
class BootstrapPluginDirTest {
    private fun dirWithBrokenPlugin(): Path {
        val dir = TestFiles.createTestTempDirectory("bootstrap-plugin-dir")
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(Path(dir, "broken.mjs")).buffered().use { it.writeString("this is not ( javascript") }
        return dir
    }

    private fun Path.cleanUp() {
        SystemFileSystem.delete(Path(this, "broken.mjs"), mustExist = false)
        SystemFileSystem.delete(this, mustExist = false)
    }

    @Test
    fun `KARANDASH_PLUGIN_DIR is scanned instead of the home plugin directory`() =
        runTest {
            val dir = dirWithBrokenPlugin()
            try {
                val env = mapOf(PluginDiscovery.PLUGIN_DIR_ENV to dir.toString())
                val result = PluginBootstrap(envReader = env::get).loadPlugins()
                assertTrue(result.failed.any { "broken.mjs" in it.path }, "failed: ${result.failed}")
            } finally {
                dir.cleanUp()
            }
        }
}
