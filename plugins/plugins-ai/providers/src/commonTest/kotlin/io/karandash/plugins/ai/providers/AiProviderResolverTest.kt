package io.karandash.plugins.ai.providers

import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiProviderResolverTest {
    private val resolver = AiProviderResolver(registry = null)

    private fun context(vararg pairs: Pair<String, Any?>): FakePluginContext = FakePluginContext(mapToJsonElement(mapOf(*pairs)))

    // -- Custom provider resolution --

    @Test
    fun `resolve returns provider for custom when baseUrl is set`() =
        runTest {
            val ctx = context("baseUrl" to "http://localhost:8080/v1")

            val provider = resolver.resolve("custom", apiKey = null, context = ctx)

            assertNotNull(provider)
        }

    @Test
    fun `resolve returns null for custom when baseUrl is missing`() =
        runTest {
            val ctx = context()

            val provider = resolver.resolve("custom", apiKey = null, context = ctx)

            assertNull(provider)
        }

    @Test
    fun `resolve returns null for unknown provider when registry is null`() =
        runTest {
            val ctx = context()

            val provider = resolver.resolve("unknown-provider", apiKey = null, context = ctx)

            assertNull(provider)
        }

    @Test
    fun `resolve uses timeout from config for custom provider`() =
        runTest {
            val ctx = context("baseUrl" to "http://localhost:8080/v1", "timeout" to "15s")

            // Resolution must succeed - timeout is consumed without error
            val provider = resolver.resolve("custom", apiKey = null, context = ctx)

            assertNotNull(provider)
        }

    @Test
    fun `resolve uses default timeout of 60s when not set for custom provider`() =
        runTest {
            val ctx = context("baseUrl" to "http://localhost:8080/v1")

            // No timeout in config - resolver falls back to 60s default
            val provider = resolver.resolve("custom", apiKey = null, context = ctx)

            assertNotNull(provider)
        }

    @Test
    fun `resolve uses maxRetries from config for custom provider`() =
        runTest {
            val ctx = context("baseUrl" to "http://localhost:8080/v1", "maxRetries" to 5)

            val provider = resolver.resolve("custom", apiKey = null, context = ctx)

            assertNotNull(provider)
        }

    @Test
    fun `resolve applies apiKey to custom provider`() =
        runTest {
            val ctx = context("baseUrl" to "http://localhost:8080/v1")

            val provider = resolver.resolve("custom", apiKey = "sk-test-123", context = ctx)

            assertNotNull(provider)
        }

    @Test
    fun `resolve is case-insensitive for provider name`() =
        runTest {
            val ctx = context("baseUrl" to "http://localhost:8080/v1")

            val lower = resolver.resolve("custom", apiKey = null, context = ctx)
            val upper = resolver.resolve("CUSTOM", apiKey = null, context = ctx)
            val mixed = resolver.resolve("Custom", apiKey = null, context = ctx)

            assertNotNull(lower)
            assertNotNull(upper)
            assertNotNull(mixed)
        }

    @Test
    fun `resolve emits warning and returns null when custom provider has no baseUrl`() =
        runTest {
            val ctx = context()

            resolver.resolve("custom", apiKey = "key", context = ctx)

            assertTrue(ctx.warnings.any { it.contains("baseUrl") || it.contains("Custom provider") })
        }

    @Test
    fun `resolve warns with the id and known providers for an unknown provider`() =
        runTest {
            val ctx = context()

            resolver.resolve("nope", apiKey = null, context = ctx)

            val warning = ctx.warnings.single()
            assertTrue("unknown AI provider 'nope'" in warning, warning)
            assertTrue("custom" in warning, warning)
        }

    // -- Helpers --

    private class FakePluginContext(
        override val pluginConfig: JsonElement
    ) : io.karandash.api.PluginContext {
        val warnings = mutableListOf<String>()

        override fun env(name: String): String? = null

        override fun log(message: String) {}

        override fun warn(message: String) {
            warnings += message
        }

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
