package io.karandash.cli.config

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.createTestTempDirectory
import io.karandash.cli.deleteTestDirectoryRecursively
import io.karandash.cli.platform.toAbsolutePath
import io.karandash.cli.platform.writeText
import io.karandash.config.pipeline.ConfigDiscovery
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigDiscoveryTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { dir ->
            deleteTestDirectoryRecursively(dir)
        }
        tempDirs.clear()
    }

    private fun createTempDir(): Path {
        val dir = createTestTempDirectory("config-discovery-test")
        tempDirs.add(dir)
        return dir
    }

    @Test
    fun `discovers config in karandash directory in current working directory`() =
        runTest {
            val tempDir = createTempDir()
            val configDir = Path(tempDir, ".karandash")
            SystemFileSystem.createDirectories(configDir)
            val configFile = Path(configDir, "config.kts")
            configFile.writeText("// test config")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            val discoveredFile = result.file
            assertNotNull(discoveredFile)
            assertEquals(configFile.toAbsolutePath().toString(), discoveredFile.toAbsolutePath().toString())
            assertEquals(".karandash/config.kts", result.matchedPattern)
        }

    @Test
    fun `discovers dotfile config in current working directory`() =
        runTest {
            val tempDir = createTempDir()
            val configFile = Path(tempDir, ".karandash.kts")
            configFile.writeText("// test config")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            val discoveredFile = result.file
            assertNotNull(discoveredFile)
            assertEquals(configFile.toAbsolutePath().toString(), discoveredFile.toAbsolutePath().toString())
            assertEquals(".karandash.kts", result.matchedPattern)
        }

    @Test
    fun `discovers plain config in current working directory`() =
        runTest {
            val tempDir = createTempDir()
            val configFile = Path(tempDir, "karandash.kts")
            configFile.writeText("// test config")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            val discoveredFile = result.file
            assertNotNull(discoveredFile)
            assertEquals(configFile.toAbsolutePath().toString(), discoveredFile.toAbsolutePath().toString())
            assertEquals("karandash.kts", result.matchedPattern)
        }

    @Test
    fun `respects priority order when multiple configs exist`() =
        runTest {
            val tempDir = createTempDir()
            // Create all three config types
            val configDir = Path(tempDir, ".karandash")
            SystemFileSystem.createDirectories(configDir)
            Path(configDir, "config.kts").writeText("// priority 1")
            Path(tempDir, ".karandash.kts").writeText("// priority 2")
            Path(tempDir, "karandash.kts").writeText("// priority 3")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNotNull(result.file)
            assertEquals(".karandash/config.kts", result.matchedPattern)
        }

    @Test
    fun `walks up to parent directory`() =
        runTest {
            val tempDir = createTempDir()
            val configFile = Path(tempDir, "karandash.kts")
            configFile.writeText("// parent config")

            val childDir = Path(tempDir, "child")
            SystemFileSystem.createDirectories(childDir)

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(childDir, formatRegistry)

            val discoveredFile = result.file
            assertNotNull(discoveredFile)
            assertEquals(configFile.toAbsolutePath().toString(), discoveredFile.toAbsolutePath().toString())
            assertEquals("karandash.kts", result.matchedPattern)
        }

    @Test
    fun `walks up multiple levels to find config`() =
        runTest {
            val tempDir = createTempDir()
            val configFile = Path(tempDir, ".karandash.kts")
            configFile.writeText("// root config")

            val level1 = Path(tempDir, "level1")
            SystemFileSystem.createDirectories(level1)
            val level2 = Path(level1, "level2")
            SystemFileSystem.createDirectories(level2)
            val level3 = Path(level2, "level3")
            SystemFileSystem.createDirectories(level3)

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(level3, formatRegistry)

            val discoveredFile = result.file
            assertNotNull(discoveredFile)
            assertEquals(configFile.toAbsolutePath().toString(), discoveredFile.toAbsolutePath().toString())
            assertEquals(".karandash.kts", result.matchedPattern)
        }

    @Test
    fun `returns null when no config found`() =
        runTest {
            val tempDir = createTempDir()
            val childDir = Path(tempDir, "child")
            SystemFileSystem.createDirectories(childDir)

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(childDir, formatRegistry)

            assertNull(result.file)
            assertNull(result.matchedPattern)
        }

    @Test
    fun `ignores directories with config names`() =
        runTest {
            val tempDir = createTempDir()
            // Create a directory named like a config file
            val fakeConfig = Path(tempDir, "karandash.kts")
            SystemFileSystem.createDirectories(fakeConfig)

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNull(result.file)
            assertNull(result.matchedPattern)
        }

    @Test
    fun `finds nearest config when configs exist at multiple levels`() =
        runTest {
            val tempDir = createTempDir()
            // Root config
            Path(tempDir, "karandash.kts").writeText("// root")

            // Child directory with its own config
            val childDir = Path(tempDir, "child")
            SystemFileSystem.createDirectories(childDir)
            val childConfig = Path(childDir, ".karandash.kts")
            childConfig.writeText("// child")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(childDir, formatRegistry)

            val discoveredFile = result.file
            assertNotNull(discoveredFile)
            assertEquals(childConfig.toAbsolutePath().toString(), discoveredFile.toAbsolutePath().toString())
            assertEquals(".karandash.kts", result.matchedPattern)
        }

    @Test
    fun `uses current working directory when no start directory provided`() =
        runTest {
            // This test just verifies the method doesn't throw when using default parameter

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry

            val result = ConfigDiscovery.discover(registry = formatRegistry)

            // We can't assert much here since we don't know the actual filesystem state,
            // but at least verify the result is not null (it has a structure)
            assertNotNull(result)
        }

    @Test
    fun `discovers yaml config in karandash directory`() =
        runTest {
            val tempDir = createTempDir()
            val configDir = Path(tempDir, ".karandash")
            SystemFileSystem.createDirectories(configDir)
            val configFile = Path(configDir, "config.yaml")
            configFile.writeText("rules: {}")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNotNull(result.file)
            assertEquals(".karandash/config.yaml", result.matchedPattern)
        }

    @Test
    fun `discovers dotfile yaml config`() =
        runTest {
            val tempDir = createTempDir()
            val configFile = Path(tempDir, ".karandash.yaml")
            configFile.writeText("rules: {}")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNotNull(result.file)
            assertEquals(".karandash.yaml", result.matchedPattern)
        }

    @Test
    fun `discovers dotfile yml config`() =
        runTest {
            val tempDir = createTempDir()
            val configFile = Path(tempDir, ".karandash.yml")
            configFile.writeText("rules: {}")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNotNull(result.file)
            assertEquals(".karandash.yml", result.matchedPattern)
        }

    @Test
    fun `discovers json config in karandash directory`() =
        runTest {
            val tempDir = createTempDir()
            val configDir = Path(tempDir, ".karandash")
            SystemFileSystem.createDirectories(configDir)
            val configFile = Path(configDir, "config.json")
            configFile.writeText("{}")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNotNull(result.file)
            assertEquals(".karandash/config.json", result.matchedPattern)
        }

    @Test
    fun `discovers dotfile json config`() =
        runTest {
            val tempDir = createTempDir()
            val configFile = Path(tempDir, ".karandash.json")
            configFile.writeText("{}")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNotNull(result.file)
            assertEquals(".karandash.json", result.matchedPattern)
        }

    @Test
    fun `kts takes priority over yaml when both exist`() =
        runTest {
            val tempDir = createTempDir()
            Path(tempDir, ".karandash.kts").writeText("// kts")
            Path(tempDir, ".karandash.yaml").writeText("rules: {}")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNotNull(result.file)
            assertEquals(".karandash.kts", result.matchedPattern)
        }

    @Test
    fun `yaml takes priority over json when both exist`() =
        runTest {
            val tempDir = createTempDir()
            Path(tempDir, ".karandash.yaml").writeText("rules: {}")
            Path(tempDir, ".karandash.json").writeText("{}")

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val formatRegistry = bootstrap.formatRegistry
            val result = ConfigDiscovery.discover(tempDir, formatRegistry)

            assertNotNull(result.file)
            assertEquals(".karandash.yaml", result.matchedPattern)
        }
}
