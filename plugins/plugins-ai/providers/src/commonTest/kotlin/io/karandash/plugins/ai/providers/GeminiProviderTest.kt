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

class GeminiProviderTest {
    private fun successResponseJson(
        content: String = "Gemini response",
        model: String = "gemini-2.0-flash",
    ): String =
        """
        {
            "id": "chatcmpl-gemini-123",
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
    fun `providerId returns gemini`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = GeminiProvider(apiKey = "test-key", httpClientEngine = engine)

            assertEquals("gemini", provider.providerId)

            provider.close()
        }

    @Test
    fun `uses correct Gemini base URL`() =
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
            val provider = GeminiProvider(apiKey = "test-key", httpClientEngine = engine)

            provider.complete(prompt = "Hello")

            assertTrue(
                capturedUrl!!.startsWith("https://generativelanguage.googleapis.com/v1beta/openai"),
                "URL should start with Gemini base URL but was: $capturedUrl",
            )

            provider.close()
        }

    @Test
    fun `complete returns parsed response`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(content = "Test Gemini output"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val provider = GeminiProvider(apiKey = "test-key", httpClientEngine = engine)

            val response = provider.complete(prompt = "Hello")

            assertEquals("Test Gemini output", response.content)
            assertEquals(15, response.totalTokens)

            provider.close()
        }

    @Test
    fun `DEFAULT_MODEL is gemini-3-5-flash-lite`() {
        assertEquals("gemini-3.5-flash-lite", GeminiProvider.DEFAULT_MODEL)
    }
}
