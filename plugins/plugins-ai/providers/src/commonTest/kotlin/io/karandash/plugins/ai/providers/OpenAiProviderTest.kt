package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiProviderException
import io.karandash.duration.KarandashDuration
import io.ktor.client.engine.HttpClientEngine
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

class OpenAiProviderTest {
    private fun successResponseJson(
        content: String = "AI response text",
        model: String = "gpt-4o-mini",
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
        engine: HttpClientEngine? = null,
        apiKey: String = "test-api-key",
        baseUrl: String = "https://api.openai.com/v1",
        maxRetries: Int = 0
    ): OpenAiProvider =
        OpenAiProvider(
            apiKey = apiKey,
            baseUrl = baseUrl,
            timeout = KarandashDuration.ofSeconds(5),
            maxRetries = maxRetries,
            httpClientEngine = engine,
        )

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

            val response = provider.complete(prompt = "Hello")

            assertEquals("AI response text", response.content)
            assertEquals(10, response.promptTokens)
            assertEquals(20, response.completionTokens)
            assertEquals(30, response.totalTokens)
            assertEquals("gpt-4o-mini", response.model)
            assertTrue(response.durationMs >= 0)

            provider.close()
        }

    @Test
    fun `complete sends correct request structure`() =
        runTest {
            val engine = MockEngine { request ->
                assertEquals(
                    "https://api.openai.com/v1/chat/completions",
                    request.url.toString()
                )
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("Bearer sk-test-key-123", request.headers[HttpHeaders.Authorization])
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
            val provider = createProvider(engine, apiKey = "sk-test-key-123")

            provider.complete(
                prompt = "Analyze this code",
                systemPrompt = "You are a code reviewer",
                model = "gpt-4o",
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

            provider.complete(prompt = "Hello", systemPrompt = "Be helpful")

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

            provider.complete(prompt = "Hello", systemPrompt = null)

            assertNotNull(requestBody)
            assertFalse(requestBody.contains("\"system\""), "Request body should not contain system role")

            provider.close()
        }

    @Test
    fun `complete sends correct request body structure`() =
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

            provider.complete(
                prompt = "Test prompt",
                model = "gpt-4o-mini",
                maxTokens = 1000,
                temperature = 0.7
            )

            assertNotNull(requestBody)
            assertTrue(requestBody.contains("\"model\":\"gpt-4o-mini\""))
            assertTrue(requestBody.contains("\"max_tokens\":1000"))
            assertTrue(requestBody.contains("\"temperature\":0.7"))
            assertTrue(requestBody.contains("\"messages\""))
            assertTrue(requestBody.contains("Test prompt"))

            provider.close()
        }

    private suspend fun sentBody(
        model: String,
        baseUrl: String = "https://api.openai.com/v1",
    ): String {
        val sent = mutableListOf<String>()
        val engine = MockEngine { request ->
            sent += request.body.toByteArray().decodeToString()
            respond(successResponseJson(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val provider = createProvider(engine, baseUrl = baseUrl)
        provider.complete(prompt = "p", model = model, maxTokens = 900, temperature = 0.2)
        provider.close()
        return sent.single()
    }

    @Test
    fun `reasoning models on api openai com get max_completion_tokens and no temperature`() =
        runTest {
            listOf("o3-mini", "o4-mini", "gpt-5", "gpt-5-mini").forEach { model ->
                val body = sentBody(model)
                assertTrue(body.contains("\"max_completion_tokens\":900"), "$model: $body")
                assertFalse(body.contains("\"max_tokens\""), "$model: $body")
                assertFalse(body.contains("\"temperature\""), "$model only accepts the default temperature: $body")
            }
        }

    @Test
    fun `older models and openai-compatible hosts keep max_tokens and temperature`() =
        runTest {
            val older = sentBody("gpt-4o-mini")
            assertTrue(older.contains("\"max_tokens\":900"), older)
            assertTrue(older.contains("\"temperature\":0.2"), older)
            val compatible = sentBody("o3-mini", baseUrl = "https://llm.internal.example/v1")
            assertTrue(compatible.contains("\"max_tokens\":900"), compatible)
            assertFalse(compatible.contains("max_completion_tokens"), compatible)
        }

    @Test
    fun `chat variants of reasoning model names keep max_tokens and temperature`() =
        runTest {
            val chat = sentBody("gpt-5-chat-latest")
            assertTrue(chat.contains("\"max_tokens\":900"), chat)
            assertTrue(chat.contains("\"temperature\":0.2"), chat)
        }

    @Test
    fun `a host that only starts with api openai com keeps max_tokens and temperature`() =
        runTest {
            val lookalike = sentBody("o3-mini", baseUrl = "https://api.openai.com.evil.example/v1")
            assertTrue(lookalike.contains("\"temperature\":0.2"), lookalike)
            assertFalse(lookalike.contains("max_completion_tokens"), lookalike)
        }

    // -- Error handling tests --

    @Test
    fun `complete throws non-retryable exception on 401`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": {"message": "Incorrect API key"}}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
            }

            assertEquals(401, exception.statusCode)
            assertFalse(exception.retryable)
            assertTrue(exception.message!!.contains("Authentication failed"))

            provider.close()
        }

    @Test
    fun `complete throws retryable exception on 429`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": {"message": "Rate limit exceeded"}}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
            }

            assertEquals(429, exception.statusCode)
            assertTrue(exception.retryable)
            assertTrue(exception.message!!.contains("Rate limited"))

            provider.close()
        }

    @Test
    fun `complete throws retryable exception on 500`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": {"message": "Internal server error"}}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
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
                    content = """{"error": {"message": "Service unavailable"}}""",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
            }

            assertEquals(503, exception.statusCode)
            assertTrue(exception.retryable)

            provider.close()
        }

    @Test
    fun `complete throws non-retryable exception on 400`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": {"message": "Bad request"}}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
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
                        content = """{"error": {"message": "Server error"}}""",
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

            val response = provider.complete(prompt = "Hello")

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
                    content = """{"error": {"message": "Server error"}}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine, maxRetries = 2)

            assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
            }

            assertEquals(3, callCount)

            provider.close()
        }

    @Test
    fun `configure maxRetries sets the retries after the first attempt`() =
        runTest {
            var callCount = 0
            val engine = MockEngine { _ ->
                callCount++
                respond(
                    content = """{"error": {"message": "Server error"}}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine, maxRetries = 2)
            provider.configure(apiKey = null, baseUrl = null, maxRetries = 1)

            assertFailsWith<AiProviderException> { provider.complete(prompt = "Hello") }

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
                    content = """{"error": {"message": "Unauthorized"}}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine, maxRetries = 2)

            assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
            }

            assertEquals(1, callCount, "Should not retry non-retryable 401 errors")

            provider.close()
        }

    // -- JSON serialization tests --

    @Test
    fun `complete handles response with missing usage field`() =
        runTest {
            val responseJson = """
            {
                "id": "chatcmpl-test",
                "model": "gpt-4o-mini",
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

            val response = provider.complete(prompt = "Hi")

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
                "model": "gpt-4o-mini",
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
                provider.complete(prompt = "Hi")
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
                provider.complete(prompt = "Hi")
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
                "model": "gpt-4o-mini",
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

            val response = provider.complete(prompt = "Hi")

            assertEquals("Works", response.content)
            assertEquals(6, response.totalTokens)

            provider.close()
        }

    // -- Provider identity and lifecycle --

    @Test
    fun `providerId returns openai`() =
        runTest {
            val provider = createProvider()

            assertEquals("openai", provider.providerId)

            provider.close()
        }

    @Test
    fun `close does not throw`() =
        runTest {
            val provider = createProvider()

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
            val provider = createProvider(engine, baseUrl = "https://custom-openai.example.com/v1/")

            provider.complete(prompt = "Hello")

            assertEquals("https://custom-openai.example.com/v1/chat/completions", capturedUrl)

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
            provider.configure(apiKey = null, baseUrl = "https://configured.example.com/v1")

            provider.complete(prompt = "Hello")

            assertEquals("https://configured.example.com/v1/chat/completions", capturedUrl)

            provider.close()
        }

    // -- Null apiKey tests --

    @Test
    fun `complete sends request without Authorization header when apiKey is null`() =
        runTest {
            val engine = MockEngine { request ->
                assertNull(
                    request.headers[HttpHeaders.Authorization],
                    "No Authorization header should be sent when apiKey is null"
                )
                respond(
                    content = successResponseJson(content = "Test response", promptTokens = 10, completionTokens = 20, totalTokens = 30),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = OpenAiProvider(apiKey = null, httpClientEngine = engine)

            val response = provider.complete(
                prompt = "Test prompt",
                systemPrompt = "System",
                model = "gpt-4o-mini",
                maxTokens = 100,
                temperature = 0.7
            )

            assertEquals("Test response", response.content)
            assertEquals(30, response.totalTokens)
            provider.close()
        }

    @Test
    fun `complete with null apiKey works on success`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(
                        content = "Success",
                        model = "custom-model",
                        promptTokens = 5,
                        completionTokens = 10,
                        totalTokens = 15
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = OpenAiProvider(apiKey = null, baseUrl = "http://openai.example.com", httpClientEngine = engine)

            val response = provider.complete(
                prompt = "Test",
                systemPrompt = null,
                model = "custom-model",
                maxTokens = 50,
                temperature = 0.5
            )

            assertEquals("Success", response.content)
            assertEquals("custom-model", response.model)
            assertEquals(15, response.totalTokens)
            provider.close()
        }

    @Test
    fun `complete with null apiKey handles 401 gracefully`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": {"message": "Unauthorized"}}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = OpenAiProvider(apiKey = null, httpClientEngine = engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(
                    prompt = "Test",
                    systemPrompt = null,
                    model = "gpt-4o-mini",
                    maxTokens = 100,
                    temperature = 0.7
                )
            }

            assertEquals(401, exception.statusCode)
            assertFalse(exception.retryable)
            assertTrue(
                exception.message!!.contains("invalid or missing API key"),
                "Error message should mention missing API key"
            )
            provider.close()
        }

    // -- configure() tests --

    @Test
    fun `configure apiKey is used when constructor apiKey is null`() =
        runTest {
            var capturedAuth: String? = null

            val engine = MockEngine { request ->
                capturedAuth = request.headers[HttpHeaders.Authorization]
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            // Constructor apiKey = null → resolveApiKey falls through to configApiKey
            val provider = OpenAiProvider(apiKey = null, httpClientEngine = engine)
            provider.configure(apiKey = "configured-key", baseUrl = null)

            provider.complete(prompt = "Hello")

            assertEquals("Bearer configured-key", capturedAuth)
            provider.close()
        }

    @Test
    fun `configure apiKey does not override constructor apiKey — constructor takes precedence`() =
        runTest {
            var capturedAuth: String? = null

            val engine = MockEngine { request ->
                capturedAuth = request.headers[HttpHeaders.Authorization]
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            // Constructor apiKey is set → resolveApiKey returns it, not the configured key
            val provider = createProvider(engine, apiKey = "constructor-key")
            provider.configure(apiKey = "configured-key", baseUrl = null)

            provider.complete(prompt = "Hello")

            assertEquals("Bearer constructor-key", capturedAuth)
            provider.close()
        }

    @Test
    fun `configure with same timeout does not recreate http client`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)
            // configure with identical timeout - client reuse, no exception
            provider.configure(apiKey = null, baseUrl = null, timeout = KarandashDuration.ofSeconds(5))

            val response = provider.complete(prompt = "Hello")

            assertEquals("AI response text", response.content)
            provider.close()
        }

    @Test
    fun `configure with different timeout recreates http client and provider still works`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(content = "After reconfigure"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)
            provider.configure(apiKey = null, baseUrl = null, timeout = KarandashDuration.ofSeconds(10))

            val response = provider.complete(prompt = "Hello")

            assertEquals("After reconfigure", response.content)
            provider.close()
        }

    // -- HTTP timeout exception handling --

    @Test
    fun `complete converts HttpRequestTimeoutException to retryable AiProviderException on last attempt`() =
        runTest {
            val engine = MockEngine { _ ->
                throw io.ktor.client.plugins.HttpRequestTimeoutException(
                    "https://api.openai.com/v1/chat/completions",
                    5_000L,
                )
            }
            val provider = createProvider(engine, maxRetries = 0)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
            }

            assertTrue(exception.retryable, "Timeout-derived exception must be retryable")
            assertTrue(
                exception.message!!.contains("timed out"),
                "Exception message should mention timeout, was: ${exception.message}",
            )
            provider.close()
        }

    @Test
    fun `complete retries on HttpRequestTimeoutException and succeeds on next attempt`() =
        runTest {
            var callCount = 0

            val engine = MockEngine { _ ->
                callCount++
                if (callCount == 1) {
                    throw io.ktor.client.plugins.HttpRequestTimeoutException(
                        "https://api.openai.com/v1/chat/completions",
                        5_000L,
                    )
                } else {
                    respond(
                        content = successResponseJson(content = "Recovered"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            val provider = createProvider(engine, maxRetries = 1)

            val response = provider.complete(prompt = "Hello")

            assertEquals("Recovered", response.content)
            assertEquals(2, callCount)
            provider.close()
        }
}
