package io.karandash.loader.wasm

import io.karandash.loader.PluginDiscovery
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class WasmPluginLoaderTest {
    @Test
    fun discoverWasmModules_returns_empty_list_for_nonexistent_directory() {
        val result = PluginDiscovery.discoverWasmModules(listOf(Path("/non/existent")))
        assertTrue(result.isEmpty())
    }
}
