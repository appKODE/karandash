package io.karandash.loader.wasm

import io.karandash.api.ModuleRegistryImpl
import io.karandash.loader.PluginDiscovery
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasmPluginLoaderEmptyStateTest {
    private val registry = ModuleRegistryImpl()
    private val loader = WasmPluginLoader(registry)

    @Test
    fun loadFromDirectories_returns_empty_result_for_nonexistent_directory() =
        runTest {
            val context = TestPluginContext()
            val result = loader.loadFromDirectories(
                listOf(Path("/non/existent/directory")),
                context,
            )
            assertEquals(emptyList(), result.loaded)
            assertEquals(emptyList(), result.failed)
            assertEquals(emptyList(), result.skipped)
        }

    @Test
    fun discoverWasmModules_returns_empty_for_nonexistent() {
        val result = PluginDiscovery.discoverWasmModules(listOf(Path("/non/existent")))
        assertTrue(result.isEmpty())
    }

    @Test
    fun unload_returns_false_for_unknown_module_id() =
        runTest {
            assertFalse(loader.unload("non-existent-id"))
        }

    @Test
    fun unloadAll_does_not_throw_when_no_modules_loaded() =
        runTest {
            loader.unloadAll() // Should not throw
        }
}
