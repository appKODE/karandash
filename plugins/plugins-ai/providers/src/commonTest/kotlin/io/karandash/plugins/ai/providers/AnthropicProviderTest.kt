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
import kotlin.test.assertTrue

class AnthropicProviderTest {
    private fun successResponseJson(
        content: String = "AI response text",
        model: String = "claude-sonnet-4-20250514",
        inputTokens: Int = 10,
        outputTokens: Int = 20
    ): String =
        """
        {
            "id": "msg_test123",
            "type": "message",
            "role": "assistant",
            "content": [
                {
                    "type": "text",
                    "text": "$content"
                }
            ],
            "model": "$model",
            "stop_reason": "end_turn",
            "stop_sequence": null,
            "usage": {
                "input_tokens": $inputTokens,
                "output_tokens": $outputTokens
            }
        }
        """.trimIndent()

    private fun createProvider(
        engine: HttpClientEngine? = null,
        apiKey: String = "test-api-key",
        baseUrl: String = "https://api.anthropic.com/v1",
        maxRetries: Int = 0
    ): AnthropicProvider =
        AnthropicProvider(
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
            assertEquals("claude-sonnet-4-20250514", response.model)
            assertTrue(response.durationMs >= 0)

            provider.close()
        }

    @Test
    fun `complete sends correct request structure with anthropic headers`() =
        runTest {
            val engine = MockEngine { request ->
                assertEquals(
                    "https://api.anthropic.com/v1/messages",
                    request.url.toString()
                )
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(
                    "sk-ant-test-key-123",
                    request.headers["x-api-key"]
                )
                assertEquals(
                    "2023-06-01",
                    request.headers["anthropic-version"]
                )
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
            val provider = createProvider(engine, apiKey = "sk-ant-test-key-123")

            provider.complete(
                prompt = "Analyze this code",
                systemPrompt = "You are a code reviewer",
                model = "claude-opus-4-20250514",
                maxTokens = 500,
                temperature = 0.5
            )

            provider.close()
        }

    @Test
    fun `complete with system prompt includes system field`() =
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
            assertTrue(requestBody.contains("\"system\""), "Request body should contain system field")
            assertTrue(requestBody.contains("Be helpful"), "Request body should contain system prompt")
            assertTrue(requestBody.contains("user"), "Request body should contain user role")
            assertTrue(requestBody.contains("Hello"), "Request body should contain user prompt")

            provider.close()
        }

    @Test
    fun `complete without system prompt omits system field`() =
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
            assertFalse(requestBody.contains("\"system\""), "unset system must be omitted, not null: $requestBody")

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
                model = "claude-sonnet-4-20250514",
                maxTokens = 1000,
                temperature = 0.7
            )

            assertNotNull(requestBody)
            assertTrue(requestBody.contains("\"model\":\"claude-sonnet-4-20250514\""))
            assertTrue(requestBody.contains("\"max_tokens\":1000"))
            assertTrue(requestBody.contains("\"temperature\":0.7"))
            assertTrue(requestBody.contains("\"messages\""))
            assertTrue(requestBody.contains("Test prompt"))

            provider.close()
        }

    // -- Error handling tests --

    @Test
    fun `complete throws non-retryable exception on 401`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": {"message": "Invalid API key"}}""",
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
    fun `complete throws non-retryable exception on 403`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": {"message": "Permission denied"}}""",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
            }

            assertEquals(403, exception.statusCode)
            assertFalse(exception.retryable)
            assertTrue(exception.message!!.contains("Permission denied"))

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
    fun `complete throws retryable exception on 529 overloaded`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = """{"error": {"message": "API overloaded"}}""",
                    status = HttpStatusCode(529, "Overloaded"),
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val exception = assertFailsWith<AiProviderException> {
                provider.complete(prompt = "Hello")
            }

            assertEquals(529, exception.statusCode)
            assertTrue(exception.retryable)
            assertTrue(exception.message!!.contains("overloaded"))

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
                        content = """{"error": {"message": "Rate limited"}}""",
                        status = HttpStatusCode.TooManyRequests,
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
    fun `complete throws on empty content array`() =
        runTest {
            val responseJson = """
            {
                "id": "msg_test",
                "type": "message",
                "role": "assistant",
                "content": [],
                "model": "claude-sonnet-4-20250514",
                "usage": {"input_tokens": 10, "output_tokens": 0}
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

            assertTrue(exception.message!!.contains("No content"))

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
                "id": "msg_test",
                "type": "message",
                "role": "assistant",
                "content": [
                    {
                        "type": "text",
                        "text": "Works"
                    }
                ],
                "model": "claude-sonnet-4-20250514",
                "created_at": 1234567890,
                "stop_reason": "end_turn",
                "stop_sequence": null,
                "usage": {
                    "input_tokens": 5,
                    "output_tokens": 1
                }
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

    @Test
    fun `complete calculates total tokens correctly`() =
        runTest {
            val engine = MockEngine { _ ->
                respond(
                    content = successResponseJson(inputTokens = 100, outputTokens = 250),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val provider = createProvider(engine)

            val response = provider.complete(prompt = "Hello")

            assertEquals(100, response.promptTokens)
            assertEquals(250, response.completionTokens)
            assertEquals(350, response.totalTokens)

            provider.close()
        }

    // -- Provider identity and lifecycle --

    @Test
    fun `providerId returns anthropic`() =
        runTest {
            val provider = createProvider()

            assertEquals("anthropic", provider.providerId)

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
            val provider = createProvider(engine, baseUrl = "https://custom-anthropic.example.com/v1/")

            provider.complete(prompt = "Hello")

            assertEquals("https://custom-anthropic.example.com/v1/messages", capturedUrl)

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

            assertEquals("https://configured.example.com/v1/messages", capturedUrl)

            provider.close()
        }

    // -- Claude 5.x compatibility --

    private suspend fun bodyFor(
        model: String,
        temperature: Double,
    ): String {
        var body = ""
        val engine = MockEngine { request ->
            body = request.body.toByteArray().decodeToString()
            respond(successResponseJson(model = model), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        createProvider(engine).complete(prompt = "p", model = model, temperature = temperature)
        return body
    }

    @Test
    fun `claude 5 models are sent without temperature`() =
        runTest {
            for (model in listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-sonnet-6-0")) {
                val body = bodyFor(model, temperature = 0.3)
                assertFalse(body.contains("temperature"), "$model must not carry temperature: $body")
            }
        }

    @Test
    fun `pre 5 claude models keep temperature`() =
        runTest {
            for (model in listOf("claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-opus-4-6")) {
                val body = bodyFor(model, temperature = 0.3)
                assertTrue(body.contains("\"temperature\":0.3"), "$model must carry temperature: $body")
            }
        }

    @Test
    fun `default model is claude sonnet 5 5`() {
        assertEquals("claude-sonnet-5-5", AnthropicProvider().defaultModel)
    }

    @Test
    fun `thinking block is skipped and all text blocks are joined`() =
        runTest {
            val body =
                """
                {"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[
                   {"type":"thinking","thinking":"","signature":"sig"},
                   {"type":"text","text":"first "},
                   {"type":"text","text":"second"}
                 ],
                 "stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":4}}
                """.trimIndent()
            val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

            val response = createProvider(engine).complete(prompt = "p", model = "claude-opus-5-5")

            assertEquals("first second", response.content)
        }

    @Test
    fun `response with only a thinking block fails loudly`() =
        runTest {
            val body =
                """{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"thinking","thinking":"x"}],"stop_reason":"max_tokens",
                 "usage":{"input_tokens":3,"output_tokens":4}}
                """.trimIndent()
            val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

            val e = assertFailsWith<AiProviderException> { createProvider(engine).complete(prompt = "p") }

            assertTrue(e.message.orEmpty().contains("stop_reason=max_tokens"), e.message)
        }

    @Test
    fun `max_tokens and refusal stop reasons are logged as warnings`() =
        runTest {
            for ((reason, expected) in listOf("max_tokens" to "truncated", "refusal" to "refused")) {
                val body =
                    """{"id":"m","type":"message","role":"assistant","model":"claude-haiku-5-5",
                     "content":[{"type":"text","text":"partial"}],"stop_reason":"$reason",
                     "usage":{"input_tokens":3,"output_tokens":4}}
                    """.trimIndent()
                val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                val logger = RecordingLogger()
                val provider = createProvider(engine)
                provider.initialize(RecordingContext(logger))

                assertEquals("partial", provider.complete(prompt = "p").content)

                assertTrue(
                    logger.lines.any { it.startsWith("WARN") && it.contains(expected) },
                    "stop_reason=$reason must warn: ${logger.lines}",
                )
            }
        }
}
