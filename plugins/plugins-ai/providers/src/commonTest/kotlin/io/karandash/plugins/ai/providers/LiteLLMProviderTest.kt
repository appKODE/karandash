package io.karandash.plugins.ai.providers

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiteLLMProviderTest {
    private fun successResponseJson(
        content: String = "LiteLLM response",
        model: String = "gpt-4o-mini",
    ): String =
        """
        {
            "id": "chatcmpl-litellm-123",
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
    fun `providerId returns litellm`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = LiteLLMProvider(apiKey = "test-key", httpClientEngine = engine)

            assertEquals("litellm", provider.providerId)

            provider.close()
        }

    @Test
    fun `uses correct LiteLLM base URL`() =
        runTest {
            var capturedUrl: String? = null

            val engine = MockEngine { request ->
                capturedUrl = request.url.toString()
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = LiteLLMProvider(apiKey = "test-key", baseUrl = "http://litellm.example.com/v1", httpClientEngine = engine)

            provider.complete(prompt = "Hello")

            assertTrue(
                capturedUrl!!.startsWith("http://litellm.example.com/v1"),
                "URL should start with configured base URL but was: $capturedUrl",
            )

            provider.close()
        }

    @Test
    fun `complete returns parsed response`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(content = "Test LiteLLM output"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = LiteLLMProvider(apiKey = "test-key", baseUrl = "http://litellm.example.com/v1", httpClientEngine = engine)

            val response = provider.complete(prompt = "Hello")

            assertEquals("Test LiteLLM output", response.content)
            assertEquals(15, response.totalTokens)

            provider.close()
        }

    @Test
    fun `DEFAULT_MODEL constant`() {
        assertEquals("gpt-5.4-mini", AiProvider.DEFAULT_MODEL)
    }

    @Test
    fun `custom baseUrl is used`() =
        runTest {
            var capturedUrl: String? = null
            val customUrl = "https://my-litellm-proxy.example.com/v1"

            val engine = MockEngine { request ->
                capturedUrl = request.url.toString()
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = LiteLLMProvider(
                apiKey = "test-key",
                baseUrl = customUrl,
                httpClientEngine = engine,
            )

            provider.complete(prompt = "Hello")

            assertTrue(
                capturedUrl!!.startsWith(customUrl),
                "URL should start with custom base URL but was: $capturedUrl",
            )

            provider.close()
        }

    @Test
    fun `ai baseUrl from config wins over the localhost default`() =
        runTest {
            val urls = mutableListOf<String>()
            val engine = MockEngine { request ->
                urls += request.url.toString()
                respond(successResponseJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val provider = LiteLLMProvider(apiKey = "test-key", httpClientEngine = engine)
            provider.configure(apiKey = null, baseUrl = "https://litellm.internal.example.com/v1")

            provider.complete(prompt = "Hello")

            assertTrue(urls.single().startsWith("https://litellm.internal.example.com/v1/"), "called ${urls.single()}")
            provider.close()
        }
}
