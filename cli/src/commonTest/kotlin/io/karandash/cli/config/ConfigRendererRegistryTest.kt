package io.karandash.cli.config

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.config.renderers.JsonConfigRenderer
import io.karandash.cli.config.renderers.KtsConfigRenderer
import io.karandash.cli.config.renderers.YamlConfigRenderer
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import io.karandash.config.pipeline.ConfigFormatRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigRendererRegistryTest {
    @Test
    fun `register and retrieve renderer`() {
        val registry = ConfigFormatRegistry()
        val renderer = YamlConfigRenderer()
        registry.registerRenderer(renderer)
        assertEquals(renderer, registry.getRenderer("yaml"))
    }

    @Test
    fun `getRenderer throws for unknown format`() {
        val registry = ConfigFormatRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.getRenderer("toml")
        }
    }

    @Test
    fun `getRendererOrNull returns null for unknown format`() {
        val registry = ConfigFormatRegistry()
        assertNull(registry.getRendererOrNull("toml"))
    }

    @Test
    fun `allRenderers returns all registered renderers`() {
        val registry = ConfigFormatRegistry()
        registry.registerRenderer(YamlConfigRenderer())
        registry.registerRenderer(JsonConfigRenderer())
        registry.registerRenderer(KtsConfigRenderer())
        val all = registry.allRenderers()
        assertEquals(3, all.size)
        assertTrue(all.any { it.formatId == "yaml" })
        assertTrue(all.any { it.formatId == "json" })
        assertTrue(all.any { it.formatId == "kts" })
    }

    @Test
    fun `fromRegistry registers built-in renderers`() =
        runTest {
            val bootstrap = PluginBootstrap(
                onFormatRegistryCreated = ::registerBuiltInRenderers,
            )
            bootstrap.loadPlugins()
            val registry = bootstrap.formatRegistry

            assertNotNull(registry.getRendererOrNull("yaml"))
            assertNotNull(registry.getRendererOrNull("json"))
            assertNotNull(registry.getRendererOrNull("kts"))
            assertEquals(3, registry.allRenderers().size)
            bootstrap.shutdown()
        }

    @Test
    fun `registerRenderer overwrites existing`() {
        val registry = ConfigFormatRegistry()
        val first = YamlConfigRenderer()
        val second = YamlConfigRenderer()
        registry.registerRenderer(first)
        registry.registerRenderer(second)
        assertEquals(second, registry.getRenderer("yaml"))
    }
}
