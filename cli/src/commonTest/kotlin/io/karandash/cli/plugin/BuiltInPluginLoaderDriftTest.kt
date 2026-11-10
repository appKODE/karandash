package io.karandash.cli.plugin

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.bootstrap.BuiltInPluginLoader
import io.karandash.sdk.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates that the built-in module registry (generated from `plugins/builtin/builtin-modules.txt`)
 * has a consistent factory list: no duplicate module IDs and a non-trivial number of
 * modules registered.
 */
class BuiltInPluginLoaderDriftTest {
    private fun stubContext(): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(emptyMap())
            override val hostVersion: String = "test"
            override val workingDirectory: String = "/tmp"
            override val logger: Logger = object : Logger {
                override fun debug(message: String) = Unit

                override fun info(message: String) = Unit

                override fun warn(message: String) = Unit

                override fun error(message: String) = Unit
            }

            override fun env(name: String): String? = null
        }

    @Test
    fun `built-in factory list should have no duplicate IDs`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = BuiltInPluginLoader(registry)
            val result = loader.loadFromDirectories(emptyList(), stubContext())

            val allIds = result.loaded + result.skipped
            val duplicates = allIds.groupBy { it }.filter { it.value.size > 1 }.keys

            assertEquals(
                emptySet(),
                duplicates,
                "Duplicate module IDs in BuiltInPluginLoader: $duplicates",
            )
        }

    @Test
    fun `built-in factory list should register a non-trivial number of modules`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val loader = BuiltInPluginLoader(registry)
            val result = loader.loadFromDirectories(emptyList(), stubContext())

            val total = result.loaded.size + result.skipped.size
            assertTrue(
                total >= 40,
                "Expected at least 40 built-in modules but got $total. " +
                    "Did you forget to add a new module to plugins/builtin/builtin-modules.txt?",
            )
        }
}
