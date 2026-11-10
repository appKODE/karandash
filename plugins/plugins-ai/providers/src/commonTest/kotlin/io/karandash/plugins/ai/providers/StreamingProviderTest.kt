package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiProviderException
import io.karandash.duration.KarandashDuration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingProviderTest {
    private val request = AiCompletionRequest(prompt = "review", model = "m")

    private fun sse(vararg events: String): MockEngine =
        MockEngine {
            respond(
                content = events.joinToString("") { "$it\n\n" },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }

    private suspend fun openAi(engine: MockEngine): OpenAiProvider =
        OpenAiProvider(
            apiKey = "k",
            baseUrl = "https://api.openai.com/v1",
            timeout = KarandashDuration.ofSeconds(5),
            maxRetries = 0,
            httpClientEngine = engine,
        ).also { it.configure(apiKey = null, baseUrl = null, stream = true) }

    private suspend fun anthropic(engine: MockEngine): AnthropicProvider =
        AnthropicProvider(
            apiKey = "k",
            baseUrl = "https://api.anthropic.com",
            timeout = KarandashDuration.ofSeconds(5),
            maxRetries = 0,
            httpClientEngine = engine,
        ).also { it.configure(apiKey = null, baseUrl = null, stream = true) }

    @Test
    fun `openai stream yields text deltas then the usage chunk`() =
        runTest {
            val engine =
                sse(
                    """event: message""",
                    """data: {"model":"gpt-4o","choices":[{"delta":{"content":"Hel"}}]}""",
                    """data: {"model":"gpt-4o","choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}""",
                    """data: {"model":"gpt-4o","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9,"prompt_tokens_details":{"cached_tokens":3}}}""",
                    "data: [DONE]",
                    """data: {"choices":[{"delta":{"content":"after done"}}]}""",
                )

            val chunks = openAi(engine).streamCompletion(request).toList()

            assertEquals("Hello", chunks.joinToString("") { it.text })
            val usage = chunks.last()
            assertEquals(7, usage.promptTokens)
            assertEquals(2, usage.completionTokens)
            assertEquals(3, usage.cacheReadTokens)
        }

    @Test
    fun `openai complete merges a streamed reply into one response`() =
        runTest {
            val engine =
                sse(
                    """data: {"model":"gpt-4o","choices":[{"delta":{"content":"Hi"}}]}""",
                    """data: {"model":"gpt-4o","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}""",
                    "data: [DONE]",
                )

            val response = openAi(engine).complete(request.prompt, null, request.model, 100, 0.0, false)

            assertEquals("Hi", response.content)
            assertEquals(4, response.promptTokens)
            assertEquals(5, response.totalTokens)
            assertEquals("gpt-4o", response.model)
        }

    @Test
    fun `openai stream carries a refusal and tolerates a truncated reply`() =
        runTest {
            val engine =
                sse(
                    """data: {"model":"gpt-4o","choices":[{"delta":{"refusal":"no"},"finish_reason":"length"}]}""",
                )

            val chunks = openAi(engine).streamCompletion(request).toList()

            assertEquals("no", chunks.single().refusal)
        }

    @Test
    fun `a truncated openai stream warns once with the final usage`() =
        runTest {
            val engine =
                sse(
                    """data: {"model":"gpt-4o","choices":[{"delta":{"content":"{\"a\":"},"finish_reason":"length"}]}""",
                    """data: {"model":"gpt-4o","choices":[],"usage":{"prompt_tokens":4,"completion_tokens":9,"total_tokens":13}}""",
                    "data: [DONE]",
                )
            val logger = RecordingLogger()
            val provider = OpenAiProvider(apiKey = "k", timeout = KarandashDuration.ofSeconds(5), maxRetries = 0, httpClientEngine = engine)
            provider.initialize(RecordingContext(logger))
            provider.configure(apiKey = null, baseUrl = null, stream = true)

            val response = provider.complete(request.prompt, null, request.model, 9, 0.0, false)

            assertTrue(response.truncated)
            val warns = logger.lines.filter { it.startsWith("WARN") && "truncated" in it }
            assertEquals(1, warns.size, logger.lines.joinToString("\n"))
            assertTrue("(9 output tokens)" in warns.single(), warns.single())
        }

    @Test
    fun `openai stream with a malformed event fails with a parse error`() =
        runTest {
            val error =
                assertFailsWith<AiProviderException> { openAi(sse("data: {not json")).streamCompletion(request).toList() }

            assertTrue(error.message.orEmpty().contains("Failed to parse stream chunk"), error.message)
        }

    @Test
    fun `a non success status on a stream fails before any chunk`() =
        runTest {
            val engine = MockEngine { respond("down", HttpStatusCode.InternalServerError) }

            assertFailsWith<AiProviderException> { openAi(engine).streamCompletion(request).toList() }
        }

    @Test
    fun `a provider without streaming returns the whole reply as one chunk`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"model":"gpt-4o","choices":[{"message":{"content":"whole"},"finish_reason":"stop"}],
                            "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val provider =
                OpenAiProvider(
                    apiKey = "k",
                    baseUrl = "https://api.openai.com/v1",
                    timeout = KarandashDuration.ofSeconds(5),
                    maxRetries = 0,
                    httpClientEngine = engine,
                )

            val chunk = provider.streamCompletion(request).toList().single()

            assertEquals("whole", chunk.text)
            assertEquals(1, chunk.promptTokens)
            assertEquals(2, chunk.completionTokens)
            assertEquals("gpt-4o", chunk.model)
        }

    @Test
    fun `anthropic stream reads usage text and stop reason events`() =
        runTest {
            val engine =
                sse(
                    "event: message_start",
                    """data: {"type":"message_start","message":{"model":"claude-x","usage":{"input_tokens":5,"output_tokens":1,"cache_read_input_tokens":2,"cache_creation_input_tokens":1}}}""",
                    """data: {"type":"ping"}""",
                    """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}}""",
                    """data: {"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"lo"}}""",
                    """data: {"type":"content_block_delta","delta":{}}""",
                    """data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":9}}""",
                )

            val chunks = anthropic(engine).streamCompletion(request).toList()

            assertEquals("Hello", chunks.joinToString("") { it.text })
            val start = chunks.first()
            assertEquals(8, start.promptTokens)
            assertEquals(2, start.cacheReadTokens)
            assertEquals(1, start.cacheWriteTokens)
            assertEquals("claude-x", start.model)
            assertEquals(9, chunks.last().completionTokens)
            assertTrue(chunks.last().truncated, "stop_reason max_tokens marks the reply truncated")
            assertNull(chunks.last().model)
        }

    @Test
    fun `anthropic stream error events are retryable only when overloaded`() =
        runTest {
            val overloaded =
                assertFailsWith<AiProviderException> {
                    anthropic(
                        sse("""data: {"type":"error","error":{"type":"overloaded_error","message":"busy"}}""")
                    ).streamCompletion(request).toList()
                }
            val invalid =
                assertFailsWith<AiProviderException> {
                    anthropic(
                        sse("""data: {"type":"error","error":{"type":"invalid_request_error","message":"bad"}}""")
                    ).streamCompletion(request).toList()
                }

            assertTrue(overloaded.retryable)
            assertTrue(!invalid.retryable)
            assertTrue(invalid.message.orEmpty().contains("invalid_request_error: bad"), invalid.message)
        }

    @Test
    fun `anthropic stream with a malformed event fails with a parse error`() =
        runTest {
            val error = assertFailsWith<AiProviderException> { anthropic(sse("data: oops")).streamCompletion(request).toList() }

            assertTrue(error.message.orEmpty().contains("Failed to parse stream event"), error.message)
        }
}
