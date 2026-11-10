package io.karandash.plugins.ai.providers

import io.karandash.api.PluginContext
import io.karandash.plugins.ai.core.AiEnvVars
import io.karandash.sdk.Logger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class StubPluginContext(
    private val envVars: Map<String, String> = emptyMap(),
) : PluginContext {
    override val pluginConfig: JsonElement = JsonObject(emptyMap())
    override val hostVersion: String = "test"
    override val workingDirectory: String = "/tmp"
    override val logger: Logger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    override fun env(name: String): String? = envVars[name]
}

class BifrostProviderTest {
    private fun successResponseJson(
        content: String = "Bifrost response",
        model: String = "gpt-4o-mini",
    ): String =
        """
        {
            "id": "chatcmpl-bifrost-123",
            "model": "$model",
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": "$content"},
                    "finish_reason": "stop"
                }
            ],
            "usage": {"prompt_tokens": 5, "completion_tokens": 10, "total_tokens": 15}
        }
        """.trimIndent()

    @Test
    fun `providerId returns bifrost`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = BifrostProvider(
                bifrostApiKey = "test-key",
                baseUrl = "https://proxy.example.com/openai",
                httpClientEngine = engine,
            )

            assertEquals("bifrost", provider.providerId)

            provider.close()
        }

    @Test
    fun `uses configured base URL`() =
        runTest {
            var capturedUrl: String? = null
            val configuredUrl = "https://proxy.example.com/openai"

            val engine = MockEngine { request ->
                capturedUrl = request.url.toString()
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = BifrostProvider(
                bifrostApiKey = "test-key",
                baseUrl = configuredUrl,
                httpClientEngine = engine,
            )

            provider.complete(prompt = "Hello")

            assertTrue(
                capturedUrl!!.startsWith(configuredUrl),
                "URL should start with configured base URL but was: $capturedUrl",
            )

            provider.close()
        }

    @Test
    fun `complete returns parsed response`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(content = "Test Bifrost output"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = BifrostProvider(
                bifrostApiKey = "test-key",
                baseUrl = "https://proxy.example.com/openai",
                httpClientEngine = engine,
            )

            val response = provider.complete(prompt = "Hello")

            assertEquals("Test Bifrost output", response.content)
            assertEquals(15, response.totalTokens)

            provider.close()
        }

    @Test
    fun `DEFAULT_MODEL constant`() {
        assertEquals("gpt-5.4-mini", AiProvider.DEFAULT_MODEL)
    }

    @Test
    fun `initialize returns false when apiKey is empty`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = BifrostProvider(
                bifrostApiKey = "",
                baseUrl = "https://proxy.example.com/openai",
                httpClientEngine = engine,
            )
            val context = StubPluginContext()

            assertFalse(provider.initialize(context))

            provider.close()
        }

    @Test
    fun `initialize returns true when apiKey is provided`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = BifrostProvider(
                bifrostApiKey = "valid-key",
                baseUrl = "https://proxy.example.com/openai",
                httpClientEngine = engine,
            )
            val context = StubPluginContext()

            assertTrue(provider.initialize(context))

            provider.close()
        }

    @Test
    fun `initialize returns true when env var is set and constructor key is empty`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = BifrostProvider(
                bifrostApiKey = "",
                baseUrl = "https://proxy.example.com/openai",
                httpClientEngine = engine,
            )
            val context = StubPluginContext(
                envVars = mapOf(AiEnvVars.BIFROST_API_KEY to "env-key"),
            )

            assertTrue(provider.initialize(context))

            provider.close()
        }

    @Test
    fun `apiKeyEnvVar returns BIFROST_API_KEY`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = BifrostProvider(
                bifrostApiKey = "test-key",
                baseUrl = "https://proxy.example.com/openai",
                httpClientEngine = engine,
            )

            assertEquals(AiEnvVars.BIFROST_API_KEY, provider.apiKeyEnvVar)

            provider.close()
        }
}
