package io.karandash.loader.native

import io.karandash.api.ModuleRegistryImpl
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-platform contract tests for [NativePluginLoader].
 *
 * These run on every declared target (JVM, macOS ARM64/x64, Linux, Windows)
 * exercising the shared contract through whichever `actual` implementation
 * is active on that platform.
 */
class NativePluginLoaderCommonTest {
    private val context = TestPluginContext()

    @Test
    fun loadFromDirectories_withEmptyDirectoryList_returnsEmptyResult() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            val result = loader.loadFromDirectories(emptyList(), context)
            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }

    @Test
    fun loadFromDirectories_withNonExistentDirectory_returnsEmptyResult() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            val result = loader.loadFromDirectories(
                listOf(Path("/non/existent/directory/native-plugin-test")),
                context,
            )
            assertTrue(result.loaded.isEmpty())
            assertTrue(result.failed.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }

    @Test
    fun unload_returnsFalseForUnknownModuleId() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            assertFalse(loader.unload("nonexistent-module"))
        }

    @Test
    fun unloadAll_doesNotThrowWhenEmpty() =
        runTest {
            val loader = NativePluginLoader(ModuleRegistryImpl())
            loader.unloadAll()
        }
}
