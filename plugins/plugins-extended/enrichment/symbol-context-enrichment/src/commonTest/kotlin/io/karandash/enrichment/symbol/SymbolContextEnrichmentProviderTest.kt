package io.karandash.enrichment.symbol

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SymbolContextEnrichmentProviderTest {
    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()

        override fun debug(message: String) {}

        override fun info(message: String) {}

        override fun warn(message: String) {
            messages.add(message)
        }

        override fun error(message: String) {}
    }

    @Test
    fun warnsOnUnknownSubBlockKeyTypo() =
        runTest {
            val logger = CapturingLogger()
            val ctx = object : PluginContext {
                override val pluginConfig =
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "changedd",
                            kotlinx.serialization.json.buildJsonObject { put("enabled", kotlinx.serialization.json.JsonPrimitive(true)) }
                        )
                    }
                override val logger: Logger = logger
                override val hostVersion = "test"
                override val workingDirectory = "/tmp"

                override fun env(name: String): String? = null

                override fun warn(message: String) {}

                override fun log(message: String) {}
            }
            SymbolContextEnrichmentProvider().initialize(ctx)

            assertTrue(logger.messages.any { it.contains("enrichment.symbol-context.changedd") && it.contains("changed") })
        }

    @Test
    fun noWarningsOnWellFormedConfig() =
        runTest {
            val logger = CapturingLogger()
            val ctx = object : PluginContext {
                override val pluginConfig =
                    kotlinx.serialization.json.buildJsonObject {
                        put("ast-grep-path", kotlinx.serialization.json.JsonPrimitive("ast-grep"))
                        put(
                            "changed",
                            kotlinx.serialization.json.buildJsonObject { put("enabled", kotlinx.serialization.json.JsonPrimitive(true)) }
                        )
                    }
                override val logger: Logger = logger
                override val hostVersion = "test"
                override val workingDirectory = "/tmp"

                override fun env(name: String): String? = null

                override fun warn(message: String) {}

                override fun log(message: String) {}
            }
            SymbolContextEnrichmentProvider().initialize(ctx)

            assertTrue(logger.messages.none { it.contains("unknown sub-block key") }, "Expected no key warnings, got: ${logger.messages}")
        }

    // ── Metadata & lifecycle ─────────────────────────────────────────────────

    @Test
    fun moduleMetadata() {
        val provider = SymbolContextEnrichmentProvider()
        assertEquals(SymbolContextEnrichmentProvider.ID, provider.id)
        assertEquals(ModuleType.ENRICHMENT_PROVIDER, provider.type)
        assertFalse(provider.defaultEnabled)
        assertTrue(provider.providedEnrichment.contains(EnrichmentKeys.SYMBOLS_CHANGED))
        assertTrue(provider.requiredEnrichment().isEmpty())
    }

    @Test
    fun initializeReturnsFalseWhenBinaryMissing() =
        runTest {
            val ctx =
                object : PluginContext {
                    override val pluginConfig =
                        kotlinx.serialization.json.buildJsonObject {
                            put("ast-grep-path", kotlinx.serialization.json.JsonPrimitive("definitely-not-ast-grep-xyz"))
                        }
                    override val logger: Logger = NoOpLogger
                    override val hostVersion = "test"
                    override val workingDirectory = "/tmp"

                    override fun env(name: String): String? = null

                    override fun warn(message: String) {}

                    override fun log(message: String) {}
                }
            assertFalse(SymbolContextEnrichmentProvider().initialize(ctx))
        }

    @Test
    fun `a missing ast-grep binary gives a skip reason`() =
        runTest {
            val provider = SymbolContextEnrichmentProvider()
            assertFalse(provider.initialize(context("""{"ast-grep-path": "definitely-not-ast-grep-xyz"}""")))

            assertTrue(provider.skipReason().orEmpty().contains("definitely-not-ast-grep-xyz"), provider.skipReason())
        }

    @Test
    fun `turned off by its own config gives no skip reason`() =
        runTest {
            val provider = SymbolContextEnrichmentProvider()
            assertFalse(provider.initialize(context("""{"changed": {"enabled": false}}""")))

            assertNull(provider.skipReason())
        }

    private fun context(config: String): PluginContext =
        object : PluginContext {
            override val pluginConfig = io.karandash.models.support.KarandashJson.Default
                .parseToJsonElement(config)
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    @Test
    fun shutdownWithoutInitializeDoesNotThrow() = runTest { SymbolContextEnrichmentProvider().shutdown() }

    @Test
    fun shutdownIsIdempotent() =
        runTest {
            val provider = SymbolContextEnrichmentProvider()
            provider.shutdown()
            provider.shutdown()
        }
}
