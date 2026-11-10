@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.providers

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiServiceKeys
import io.karandash.duration.KarandashDuration
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ai.timeout` as a bare number means seconds, on every path; sub-second values survive.
 */
class AiTimeoutConfigTest {
    @Test
    fun `call timeout times attempts past the execution timeout warns`() {
        val minute = KarandashDuration.ofMinutes(1)
        assertTrue(retriesPastDeadline(KarandashDuration.ofMinutes(2), 2, KarandashDuration.ofMinutes(5)) != null)
        assertEquals(null, retriesPastDeadline(minute, 3, KarandashDuration.ofMinutes(4)))
        assertEquals(null, retriesPastDeadline(minute, 3, null))
    }

    @Test
    fun `bare number is seconds and unit strings keep working`() {
        val warnings = mutableListOf<String>()
        assertEquals(KarandashDuration.ofSeconds(120), parseAiTimeout("120", warnings::add))
        assertEquals(KarandashDuration.ofSeconds(30), parseAiTimeout("30", warnings::add))
        assertEquals(KarandashDuration.ofMinutes(8), parseAiTimeout("8m", warnings::add))
        assertEquals(KarandashDuration.ofMillis(1500), parseAiTimeout("1500ms", warnings::add))
        assertEquals(emptyList(), warnings)
        assertEquals(KarandashDuration.ofSeconds(30_000), parseAiTimeout("30000", warnings::add))
        assertEquals(1, warnings.size)
        assertTrue("\"30000ms\"" in warnings.single() && "seconds" in warnings.single())
    }

    @Test
    fun `the run handle gets the configured timeout`() =
        runTest {
            for ((raw, expected) in listOf(
                JsonPrimitive(120) to KarandashDuration.ofSeconds(120),
                JsonPrimitive("8m") to KarandashDuration.ofMinutes(8),
                JsonPrimitive("500ms") to KarandashDuration.ofMillis(500),
                JsonPrimitive(1200) to KarandashDuration.ofSeconds(1200),
            )) {
                val spawned = mutableListOf<KarandashDuration>()
                val registry = ModuleRegistryImpl().also { it.register(Probe(spawned)) }
                val session = registry.forRun()
                session.provide(
                    AiServiceKeys.AI_CONFIG,
                    JsonObject(
                        mapOf(
                            "provider" to JsonPrimitive("openai"),
                            "model" to JsonPrimitive("m"),
                            "apiKey" to JsonPrimitive("k"),
                            "timeout" to raw
                        )
                    ),
                )
                val warnings = mutableListOf<String>()
                RunAiProviderSource(session, registry) { Ctx(it, Recording(warnings)) }.provider(null)
                assertEquals(listOf(expected), spawned, "ai.timeout=$raw")
                session.close()
            }
        }

    private class Probe(
        private val spawned: MutableList<KarandashDuration>,
        timeout: KarandashDuration = KarandashDuration.ofSeconds(30),
    ) : OpenAiProvider(apiKey = "k", timeout = timeout, maxRetries = 0) {
        override fun spawn(
            timeout: KarandashDuration,
            maxRetries: Int,
            engine: HttpClientEngine?,
        ): AbstractAiProvider {
            spawned += timeout
            return Probe(spawned, timeout)
        }
    }

    private class Recording(
        private val warnings: MutableList<String>,
    ) : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private class Ctx(
        override val pluginConfig: JsonElement,
        override val logger: Logger = NoOpLogger,
    ) : PluginContext {
        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
