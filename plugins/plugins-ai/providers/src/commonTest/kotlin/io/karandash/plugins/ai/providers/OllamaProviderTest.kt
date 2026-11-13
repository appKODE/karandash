package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiProviderException
import io.karandash.duration.KarandashDuration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OllamaProviderTest {
    private fun successResponseJson(
        content: String = "AI response text",
        model: String = "llama3.2",
        promptTokens: Int = 10,
        completionTokens: Int = 20,
        totalTokens: Int = 30
    ): String =
        """
        {
            "id": "chatcmpl-test123",
            "object": "chat.completion",
            "model": "$model",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "$content"
                    },
                    "finish_reason": "stop"
                }
            ],
            "usage": {
                "prompt_tokens": $promptTokens,
                "completion_tokens": $completionTokens,
                "total_tokens": $totalTokens
            }
        }
        """.trimIndent()

    private fun createProvider(
        engine: MockEngine,
        baseUrl: String = "http://ollama:11434/v1",
        timeout: KarandashDuration = KarandashDuration.ofSeconds(5),
        maxRetries: Int = 0
    ): OllamaProvider =
        OllamaProvider(
            baseUrl = baseUrl,
            timeout = timeout,
            maxRetries = maxRetries,
            httpClientEngine = engine,
        )

    // -- Local host --

    @Test
    fun `a loopback OLLAMA_HOST is reached on its v1 endpoint without allow_localhost`() =
        runTest {
            val urls = mutableListOf<String>()
            val engine = MockEngine { request ->
                urls += request.url.toString()
                respond(successResponseJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val provider = createProvider(engine, baseUrl = OllamaProvider.baseUrlForHost("http://localhost:11434/"))

            provider.complete(prompt = "Hello", model = "llama3.2")

            assertEquals(listOf("http://localhost:11434/v1/chat/completions"), urls)
        }

    // -- Successful completion tests --

    @Test
    fun `complete returns parsed response on success`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val response = provider.complete(prompt = "Hello", model = "llama3.2")

            assertEquals("AI response text", response.content)
            assertEquals(10, response.promptTokens)
            assertEquals(20, response.completionTokens)
            assertEquals(30, response.totalTokens)
            assertEquals("llama3.2", response.model)
            assertTrue(response.durationMs >= 0)

            provider.close()
        }

    @Test
    fun `complete sends request without Authorization header`() =
        runTest {
            val engine = MockEngine { request ->
                assertNull(
                    request.headers[HttpHeaders.Authorization],
                    "Ollama should not send Authorization header"
                )
                assertEquals(
                    "http://ollama:11434/v1/chat/completions",
                    request.url.toString()
                )
                assertEquals(HttpMethod.Post, request.method)
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            provider.complete(prompt = "Test prompt", model = "llama3.2")

            provider.close()
        }

    @Test
    fun `complete sends correct request structure`() =
        runTest {
            val engine = MockEngine { request ->
                assertEquals(
                    "http://ollama:11434/v1/chat/completions",
                    request.url.toString()
                )
                assertEquals(HttpMethod.Post, request.method)
                assertTrue(
                    request.body.contentType
                        .toString()
                        .contains("application/json"),
                    "Content type should be application/json"
                )
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            provider.complete(
                prompt = "Analyze this code",
                systemPrompt = "You are a code reviewer",
                model = "llama3.2",
                maxTokens = 500,
                temperature = 0.5
            )

            provider.close()
        }

    @Test
    fun `complete with system prompt includes system message`() =
        runTest {
            var requestBody: String? = null

            val engine = MockEngine { request ->
                requestBody = request.body.toByteArray().decodeToString()
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            provider.complete(prompt = "Hello", systemPrompt = "Be helpful", model = "llama3.2")

            assertNotNull(requestBody)
            assertTrue(requestBody.contains("system"), "Request body should contain system role")
            assertTrue(requestBody.contains("Be helpful"), "Request body should contain system prompt")
            assertTrue(requestBody.contains("user"), "Request body should contain user role")
            assertTrue(requestBody.contains("Hello"), "Request body should contain user prompt")

            provider.close()
        }

    @Test
    fun `complete without system prompt skips system message`() =
        runTest {
            var requestBody: String? = null

            val engine = MockEngine { request ->
                requestBody = request.body.toByteArray().decodeToString()
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            provider.complete(prompt = "Hello", systemPrompt = null, model = "llama3.2")

            assertNotNull(requestBody)
            assertFalse(requestBody.contains("\"system\""), "Request body should not contain system role")

            provider.close()
        }

    // -- Error handling tests --

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `complete names the host of a refused connection like every openai-compatible provider`() =
        runTest {
            val engine = MockEngine { _ ->
                throw Exception("Connection refused")
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello", model = "llama3.2")
            }

            assertFalse(exception.retryable, "A refused connection is not retried, as for the other OpenAI-compatible providers")
            assertEquals("unreachable: ollama (connection refused)", exception.message)

            provider.close()
        }

    @Test
    fun `complete throws non-retryable exception on 404 model not found`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": "model not found"}""",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello", model = "nonexistent-model")
            }

            assertEquals(404, exception.statusCode)
            assertFalse(exception.retryable)
            assertTrue(exception.message!!.contains("Model not found"))

            provider.close()
        }

    @Test
    fun `complete throws retryable exception on 500`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": "Internal server error"}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello", model = "llama3.2")
            }

            assertEquals(500, exception.statusCode)
            assertTrue(exception.retryable)
            assertTrue(exception.message!!.contains("server error"))

            provider.close()
        }

    @Test
    fun `complete throws retryable exception on 503`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": "Service unavailable"}""",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello", model = "llama3.2")
            }

            assertEquals(503, exception.statusCode)
            assertTrue(exception.retryable)

            provider.close()
        }

    @Test
    fun `complete throws retryable exception on 429 like every other provider`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": "rate limited"}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello", model = "llama3.2")
            }

            assertEquals(429, exception.statusCode)
            assertTrue(exception.retryable)

            provider.close()
        }

    @Test
    fun `complete throws non-retryable exception on 400`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": "Bad request"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello", model = "llama3.2")
            }

            assertEquals(400, exception.statusCode)
            assertFalse(exception.retryable)

            provider.close()
        }

    // -- Retry logic tests --

    @Test
    fun `complete retries on retryable errors and succeeds`() =
        runTest {
            var callCount = 0

            val engine = MockEngine { _ ->
                callCount++
                if (callCount == 1) {
                    respond(
                        content = """{"error": "Server error"}""",
                        status = HttpStatusCode.InternalServerError,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                } else {
                    respond(
                        content = successResponseJson(content = "Retry succeeded"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            val provider = createProvider(engine, maxRetries = 2)

            val response = provider.complete(prompt = "Hello", model = "llama3.2")

            assertEquals("Retry succeeded", response.content)
            assertEquals(2, callCount)

            provider.close()
        }

    @Test
    fun `complete exhausts retries on persistent errors`() =
        runTest {
            var callCount = 0

            val engine = MockEngine { _ ->
                callCount++
                respond(
                    content = """{"error": "Server error"}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine, maxRetries = 1)

            assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello", model = "llama3.2")
            }

            assertEquals(2, callCount)

            provider.close()
        }

    @Test
    fun `complete does not retry non-retryable errors`() =
        runTest {
            var callCount = 0

            val engine = MockEngine { _ ->
                callCount++
                respond(
                    content = """{"error": "Model not found"}""",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine, maxRetries = 2)

            assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello", model = "llama3.2")
            }

            assertEquals(1, callCount, "Should not retry non-retryable 404 errors")

            provider.close()
        }

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `complete does not retry a refused connection like every other provider`() =
        runTest {
            var callCount = 0

            val engine = MockEngine { _ ->
                callCount++
                throw Exception("Connection refused")
            }
            val provider = createProvider(engine, maxRetries = 2)

            assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello", model = "llama3.2")
            }

            assertEquals(1, callCount, "A refused connection is not retried: the host will not answer a retry either")

            provider.close()
        }

    // -- JSON serialization tests --

    @Test
    fun `complete handles response with missing usage field`() =
        runTest {
            val responseJson = """
            {
                "id": "chatcmpl-test",
                "model": "llama3.2",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": "Hello"},
                        "finish_reason": "stop"
                    }
                ]
            }
            """.trimIndent()

            val engine = MockEngine { _ ->
                respond(
                    content = responseJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val response = provider.complete(prompt = "Hi", model = "llama3.2")

            assertEquals("Hello", response.content)
            assertEquals(0, response.promptTokens)
            assertEquals(0, response.completionTokens)
            assertEquals(0, response.totalTokens)

            provider.close()
        }

    @Test
    fun `complete throws on empty choices`() =
        runTest {
            val responseJson = """
            {
                "id": "chatcmpl-test",
                "model": "llama3.2",
                "choices": [],
                "usage": {"prompt_tokens": 10, "completion_tokens": 0, "total_tokens": 10}
            }
            """.trimIndent()

            val engine = MockEngine { _ ->
                respond(
                    content = responseJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hi", model = "llama3.2")
            }

            assertTrue(exception.message!!.contains("No choices"))

            provider.close()
        }

    @Test
    fun `complete throws on malformed JSON response`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = "not valid json {{{",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hi", model = "llama3.2")
            }

            assertTrue(exception.message!!.contains("Failed to parse"))

            provider.close()
        }

    @Test
    fun `complete handles response with extra unknown fields`() =
        runTest {
            val responseJson = """
            {
                "id": "chatcmpl-test",
                "object": "chat.completion",
                "created": 1234567890,
                "model": "llama3.2",
                "system_fingerprint": "fp_abc123",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": "Works"},
                        "logprobs": null,
                        "finish_reason": "stop"
                    }
                ],
                "usage": {"prompt_tokens": 5, "completion_tokens": 1, "total_tokens": 6}
            }
            """.trimIndent()

            val engine = MockEngine { _ ->
                respond(
                    content = responseJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val response = provider.complete(prompt = "Hi", model = "llama3.2")

            assertEquals("Works", response.content)
            assertEquals(6, response.totalTokens)

            provider.close()
        }

    // -- Provider identity and lifecycle --

    @Test
    fun `providerId returns ollama`() =
        runTest {
            val engine = MockEngine { _ ->
                respond("", status = HttpStatusCode.OK)
            }
            val provider = createProvider(engine)

            assertEquals("ollama", provider.providerId)

            provider.close()
        }

    @Test
    fun `close does not throw`() =
        runTest {
            val engine = MockEngine { _ ->
                respond("", status = HttpStatusCode.OK)
            }
            val provider = createProvider(engine)

            // Should not throw
            provider.close()
        }

    @Test
    fun `custom baseUrl is used in request`() =
        runTest {
            var capturedUrl: String? = null

            val engine = MockEngine { request ->
                capturedUrl = request.url.toString()
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine, baseUrl = "http://custom-ollama:8080/v1/")

            provider.complete(prompt = "Hello", model = "llama3.2")

            assertEquals("http://custom-ollama:8080/v1/chat/completions", capturedUrl)

            provider.close()
        }

    @Test
    fun `configured ai baseUrl overrides the default endpoint`() =
        runTest {
            var capturedUrl: String? = null

            val engine = MockEngine { request ->
                capturedUrl = request.url.toString()
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)
            provider.configure(apiKey = null, baseUrl = "http://10.0.0.5:11434/v1")

            provider.complete(prompt = "Hello", model = "llama3.2")

            assertEquals("http://10.0.0.5:11434/v1/chat/completions", capturedUrl)

            provider.close()
        }

    @Test
    fun `httpClient is initialized after construction`() =
        runTest {
            val provider = OllamaProvider()

            assertNotNull(provider.httpClient)

            provider.close()
        }
}
