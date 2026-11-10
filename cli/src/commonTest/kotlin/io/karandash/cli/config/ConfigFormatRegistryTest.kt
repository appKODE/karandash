package io.karandash.cli.config

import io.karandash.api.module.ConfigFormatModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.sdk.config.anyToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigFormatRegistryTest {
    private fun stubModule(
        id: String,
        extensions: Set<String> = emptySet()
    ): ConfigFormatModule =
        object : ConfigFormatModule {
            override val formatId = id
            override val fileExtensions = extensions

            override suspend fun parseToJsonElement(content: String): JsonElement = anyToJsonElement(emptyMap<String, Any?>())
        }

    @Test
    fun `detectFormat returns yaml for yml extension`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val registry = bootstrap.formatRegistry
            assertEquals("yaml", registry.detectFormat("config.yml"))
        }

    @Test
    fun `detectFormat returns yaml for yaml extension`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val registry = bootstrap.formatRegistry
            assertEquals("yaml", registry.detectFormat("config.yaml"))
        }

    @Test
    fun `detectFormat returns json for json extension`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val registry = bootstrap.formatRegistry
            assertEquals("json", registry.detectFormat("config.json"))
        }

    @Test
    fun `detectFormat falls back to yaml for unknown extension`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val registry = bootstrap.formatRegistry
            assertEquals("yaml", registry.detectFormat("config.unknown"))
        }

    @Test
    fun `register and retrieve module`() {
        val registry = ConfigFormatRegistry()
        val module = stubModule("toml", setOf("toml"))
        registry.register(module)
        assertEquals(module, registry.getModule("toml"))
    }

    @Test
    fun `getModule throws for unknown format`() {
        val registry = ConfigFormatRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.getModule("toml")
        }
    }

    @Test
    fun `builtIns registered at construction`() {
        val module = stubModule("toml", setOf("toml"))
        val registry = ConfigFormatRegistry(listOf(module))
        assertEquals(module, registry.getModule("toml"))
    }

    @Test
    fun `register overwrites existing`() {
        val registry = ConfigFormatRegistry()
        val first = stubModule("toml", setOf("toml"))
        val second = stubModule("toml", setOf("toml"))
        registry.register(first)
        registry.register(second)
        assertEquals(second, registry.getModule("toml"))
    }

    @Test
    fun `detectFormat returns kts for kts extension`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val registry = bootstrap.formatRegistry
            assertEquals("kts", registry.detectFormat("config.kts"))
        }

    @Test
    fun `detectFormat falls back to yaml for path with no extension`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val registry = bootstrap.formatRegistry
            assertEquals("yaml", registry.detectFormat("Makefile"))
        }

    @Test
    fun `allModules returns all registered modules`() {
        val yaml = stubModule("yaml", setOf("yaml", "yml"))
        val json = stubModule("json", setOf("json"))
        val registry = ConfigFormatRegistry(listOf(yaml, json))
        val all = registry.allModules()
        assertEquals(2, all.size)
        assertTrue(all.contains(yaml))
        assertTrue(all.contains(json))
    }

    @Test
    fun `builtIn registers yaml json and kts`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val registry = bootstrap.formatRegistry
            assertEquals("yaml", registry.getModule("yaml").formatId)
            assertEquals("json", registry.getModule("json").formatId)
            assertEquals("kts", registry.getModule("kts").formatId)
            assertEquals(3, registry.allModules().size)
        }

    @Test
    fun `detectFormat uses fileExtensions from registered modules`() {
        val module = stubModule("toml", setOf("toml", "tml"))
        val registry = ConfigFormatRegistry(listOf(module))
        assertEquals("toml", registry.detectFormat("config.toml"))
        assertEquals("toml", registry.detectFormat("config.tml"))
    }

    @Test
    fun `allExtensions returns combined extensions from all modules`() {
        val yaml = stubModule("yaml", setOf("yaml", "yml"))
        val json = stubModule("json", setOf("json"))
        val registry = ConfigFormatRegistry(listOf(yaml, json))
        val extensions = registry.allExtensions()
        assertEquals(setOf("yaml", "yml", "json"), extensions)
    }
}
