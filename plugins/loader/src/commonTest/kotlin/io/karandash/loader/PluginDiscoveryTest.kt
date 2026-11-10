package io.karandash.loader

import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginDiscoveryTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        // Create temp directory
        val temp = TestFiles.createTestTempDirectory("plugin-discovery-base")
        val randomSuffix = (0..999999).random()
        val testDir = Path("$temp/plugin-discovery-test-$randomSuffix")
        createDirectory(testDir)
        tempDir = testDir
    }

    @AfterTest
    fun cleanup() {
        deleteRecursively(tempDir)
    }

    @Test
    fun `discovers native libraries with different extensions`() {
        createFile(Path("$tempDir/plugin.so"))
        createFile(Path("$tempDir/plugin.dylib"))
        createFile(Path("$tempDir/plugin.dll"))
        createFile(Path("$tempDir/readme.txt"))

        val libs = PluginDiscovery.discoverNativeLibs(listOf(tempDir))

        assertEquals(3, libs.size)
        assertEquals("plugin.dll", libs[0].name)
        assertEquals("plugin.dylib", libs[1].name)
        assertEquals("plugin.so", libs[2].name)
    }

    @Test
    fun `ignores non-native-library files`() {
        createFile(Path("$tempDir/plugin.so"))
        createFile(Path("$tempDir/plugin.jar"))
        createFile(Path("$tempDir/plugin.txt"))

        val libs = PluginDiscovery.discoverNativeLibs(listOf(tempDir))

        assertEquals(1, libs.size)
        assertEquals("plugin.so", libs[0].name)
    }

    @Test
    fun `returns empty list for native libs in non-existent directory`() {
        val nonExistent = Path("$tempDir/does-not-exist")
        val libs = PluginDiscovery.discoverNativeLibs(listOf(nonExistent))

        assertTrue(libs.isEmpty())
    }

    @Test
    fun `native libs are sorted by name`() {
        createFile(Path("$tempDir/z-lib.so"))
        createFile(Path("$tempDir/a-lib.dylib"))
        createFile(Path("$tempDir/m-lib.dll"))

        val libs = PluginDiscovery.discoverNativeLibs(listOf(tempDir))

        assertEquals(3, libs.size)
        assertEquals("a-lib.dylib", libs[0].name)
        assertEquals("m-lib.dll", libs[1].name)
        assertEquals("z-lib.so", libs[2].name)
    }

    @Test
    fun `discovers JavaScript modules with mjs and js extensions`() {
        createFile(Path("$tempDir/plugin.mjs"))
        createFile(Path("$tempDir/plugin.js"))
        createFile(Path("$tempDir/readme.txt"))

        val modules = PluginDiscovery.discoverJsModules(listOf(tempDir))

        assertEquals(2, modules.size)
        assertEquals("plugin.js", modules[0].name)
        assertEquals("plugin.mjs", modules[1].name)
    }

    @Test
    fun `ignores non-javascript-module files`() {
        createFile(Path("$tempDir/plugin.js"))
        createFile(Path("$tempDir/plugin.jar"))
        createFile(Path("$tempDir/plugin.txt"))

        val modules = PluginDiscovery.discoverJsModules(listOf(tempDir))

        assertEquals(1, modules.size)
        assertEquals("plugin.js", modules[0].name)
    }

    @Test
    fun `returns empty list for js modules in non-existent directory`() {
        val nonExistent = Path("$tempDir/does-not-exist")
        val modules = PluginDiscovery.discoverJsModules(listOf(nonExistent))

        assertTrue(modules.isEmpty())
    }

    @Test
    fun `js modules are sorted by name`() {
        createFile(Path("$tempDir/z-module.js"))
        createFile(Path("$tempDir/a-module.mjs"))
        createFile(Path("$tempDir/m-module.js"))

        val modules = PluginDiscovery.discoverJsModules(listOf(tempDir))

        assertEquals(3, modules.size)
        assertEquals("a-module.mjs", modules[0].name)
        assertEquals("m-module.js", modules[1].name)
        assertEquals("z-module.js", modules[2].name)
    }

    @Test
    fun `ignores directories named with native lib extensions`() {
        createDirectory(Path("$tempDir/fake.so"))
        createDirectory(Path("$tempDir/fake.dylib"))
        createFile(Path("$tempDir/real.dll"))

        val libs = PluginDiscovery.discoverNativeLibs(listOf(tempDir))

        assertEquals(1, libs.size)
        assertEquals("real.dll", libs[0].name)
    }

    @Test
    fun `ignores directories named with js extensions`() {
        createDirectory(Path("$tempDir/fake.js"))
        createDirectory(Path("$tempDir/fake.mjs"))
        createFile(Path("$tempDir/real.mjs"))

        val modules = PluginDiscovery.discoverJsModules(listOf(tempDir))

        assertEquals(1, modules.size)
        assertEquals("real.mjs", modules[0].name)
    }

    @Test
    fun `defaultPluginDirPath returns expected path`() {
        val path = PluginDiscovery.defaultPluginDirPath(env = { null })
        assertTrue(path.endsWith("/.karandash/plugins"))
    }

    @Test
    fun `KARANDASH_PLUGIN_DIR replaces the default plugin directory`() {
        val env = mapOf(PluginDiscovery.PLUGIN_DIR_ENV to " /opt/karandash-plugins ")
        assertEquals("/opt/karandash-plugins", PluginDiscovery.defaultPluginDirPath(env = env::get))
        assertTrue(PluginDiscovery.defaultPluginDirPath(env = { "  " }).endsWith("/.karandash/plugins"))
    }

    @Test
    fun `defaultPluginDir returns valid PlatformFile`() {
        val dir = PluginDiscovery.defaultPluginDir()
        assertTrue(dir.toString().endsWith("/.karandash/plugins"))
    }
}

// Helper functions for test file operations
private fun createFile(file: Path) {
    file.writeBytes(ByteArray(0))
}

private fun createDirectory(dir: Path) {
    createDirectories(dir)
}

private fun deleteRecursively(file: Path) {
    if (file.isDirectory()) {
        file.listFiles()?.forEach { deleteRecursively(it) }
    }
    deleteIfExists(file)
}
