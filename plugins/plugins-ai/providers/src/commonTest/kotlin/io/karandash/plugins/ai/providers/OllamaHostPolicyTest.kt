@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.providers

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiServiceKeys
import io.karandash.duration.KarandashDuration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Ollama reaches only loopback and private-network hosts and the hosts on `ai.ollama.allowHosts`
 * (default `host.docker.internal`), never metadata, link-local or other public hosts.
 */
class OllamaHostPolicyTest {
    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
            |"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    private fun engine() = MockEngine { respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

    private fun provider(
        baseUrl: String,
        engine: MockEngine,
    ) = OllamaProvider(baseUrl = baseUrl, timeout = KarandashDuration.ofSeconds(5), maxRetries = 0, httpClientEngine = engine)

    @Test
    fun `loopback private and dotless hosts are reached`() =
        runTest {
            for (url in listOf(
                "http://localhost:11434/v1",
                "http://127.0.0.1:11434/v1",
                "http://192.168.1.20:11434/v1",
                "http://ollama:11434/v1"
            )) {
                val engine = engine()
                provider(url, engine).complete(prompt = "hi", model = "m")
                assertEquals(1, engine.requestHistory.size, url)
            }
        }

    @Test
    fun `metadata link-local and public hosts are refused before any request`() =
        runTest {
            for (url in listOf(
                "http://169.254.169.254/v1",
                "http://metadata.google.internal/v1",
                "http://[fe80::1]:11434/v1",
                "https://ollama.example.com/v1",
            )) {
                val engine = engine()
                val e = assertFailsWith<AiProviderException>(url) { provider(url, engine).complete(prompt = "hi", model = "m") }
                assertTrue(e.message.orEmpty().contains("loopback or private"), "$url: ${e.message}")
                assertEquals(0, engine.requestHistory.size, url)
            }
        }

    @Test
    fun `host docker internal is reached by default`() =
        runTest {
            val engine = engine()
            provider("http://host.docker.internal:11434/v1", engine).complete(prompt = "hi", model = "m")
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun `a host on ai ollama allowHosts is reached and other public hosts stay refused`() =
        runTest {
            val config = ollamaBlock("GPU-box.example.com")
            val engine = engine()
            val allowed = provider("https://gpu-box.example.com/v1", engine)
            assertTrue(allowed.initialize(Ctx(config)))
            allowed.complete(prompt = "hi", model = "m")
            assertEquals(1, engine.requestHistory.size)

            val other = provider("https://ollama.example.com/v1", engine)
            assertTrue(other.initialize(Ctx(config)))
            assertFailsWith<AiProviderException> { other.complete(prompt = "hi", model = "m") }
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun `a configured allowHosts list replaces the default`() =
        runTest {
            val engine = engine()
            val ollama = provider("http://host.docker.internal:11434/v1", engine)
            assertTrue(ollama.initialize(Ctx(ollamaBlock("gpu-box.example.com"))))
            assertFailsWith<AiProviderException> { ollama.complete(prompt = "hi", model = "m") }
            assertEquals(0, engine.requestHistory.size)
        }

    @Test
    fun `the run handle gets ai ollama allowHosts from the ai block`() =
        runTest {
            val engine = engine()
            val registry = ModuleRegistryImpl().also { it.register(provider("http://localhost:11434/v1", engine)) }
            val session = registry.forRun()
            val ai = mapOf(
                "provider" to JsonPrimitive("ollama"),
                "model" to JsonPrimitive("m"),
                "baseUrl" to JsonPrimitive("https://gpu-box.example.com/v1"),
            ) + ollamaBlock("gpu-box.example.com")
            session.provide(AiServiceKeys.AI_CONFIG, JsonObject(ai))
            val handle = checkNotNull(RunAiProviderSource(session, registry) { Ctx(it) }.provider(null))

            handle.complete(AiCompletionRequest(prompt = "hi", model = "m"))

            assertEquals(1, engine.requestHistory.size)
            session.close()
        }

    private fun ollamaBlock(vararg hosts: String): JsonObject =
        JsonObject(mapOf("ollama" to JsonObject(mapOf("allowHosts" to JsonArray(hosts.map(::JsonPrimitive))))))

    private class Ctx(
        override val pluginConfig: JsonElement,
    ) : PluginContext {
        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
