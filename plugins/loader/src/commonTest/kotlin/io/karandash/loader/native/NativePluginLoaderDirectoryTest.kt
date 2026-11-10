package io.karandash.loader.native

import io.karandash.api.ModuleRegistryImpl
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [NativePluginLoader] on every target (JNA on JVM, dlopen on native).
 *
 * All tests use invalid or empty files to exercise error-handling paths
 * without requiring real native libraries.
 */
class NativePluginLoaderDirectoryTest {
    private val tempDir = TestFiles.createTestTempDirectory("native-loader-test")
    private val context = TestPluginContext()

    @AfterTest
    fun cleanup() {
        TestFiles.deleteRecursively(tempDir)
    }

    @Test
    fun loadFromDirectories_returnsEmptyForEmptyDirectory() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            val result = loader.loadFromDirectories(listOf(tempDir), context)
            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }

    @Test
    fun loadFromDirectories_returnsEmptyForNonExistentDirectory() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            val result = loader.loadFromDirectories(listOf(Path("/non/existent/native-loader-path")), context)
            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }

    @Test
    fun loadFromDirectories_returnsEmptyForEmptyDirectoryList() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            val result = loader.loadFromDirectories(emptyList(), context)
            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }

    @Test
    fun loadFromDirectories_reportsFailureForInvalidLibraryContent() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())

            // Create fake native library files with all recognised extensions.
            // PluginDiscovery.discoverNativeLibs scans for .so, .dylib, and .dll
            // regardless of the host OS, so at least one file is always discovered.
            listOf("fake-plugin.dylib", "fake-plugin.so", "fake-plugin.dll").forEach { name ->
                TestFiles.writeText(Path(tempDir, name), "not a real native library")
            }

            val result = loader.loadFromDirectories(listOf(tempDir), context)

            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isNotEmpty(), "Expected at least one failure for invalid library content")
        }

    @Test
    fun unload_returnsFalseForUnknownModuleId() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            assertFalse(loader.unload("nonexistent-native-module"))
        }

    @Test
    fun unloadAll_doesNotThrowWhenNoModulesLoaded() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            loader.unloadAll()
        }

    @Test
    fun loadFromDirectories_mixedExistentAndNonExistentDirectoriesDoesNotThrow() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            val result = loader.loadFromDirectories(
                listOf(
                    tempDir,
                    Path("/non/existent/path/a"),
                    Path("/non/existent/path/b"),
                ),
                context,
            )
            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }
}
