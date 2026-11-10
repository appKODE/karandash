@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.providers

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiServiceKeys
import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.providers.AbstractAiProvider
import io.karandash.plugins.ai.providers.AnthropicProvider
import io.karandash.plugins.ai.providers.OllamaProvider
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.karandash.plugins.ai.providers.RunAiProviderSource
import io.karandash.plugins.ai.stub.StubAiProvider
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AI provider contract: with `ai.stream: true` a provider returns the same result (text, model, usage) as without,
 * on the provider's own wire format served by a [MockEngine]; and the opt-in `stub` provider answers from its file.
 */
class ProviderContractTest {
    private val request = AiCompletionRequest(prompt = "diff", systemPrompt = "Review it", model = "m")

    /** Answers a JSON reply, or the SSE body when the request asked for a stream. */
    private fun engine(
        json: String,
        sse: String,
    ) = MockEngine { call ->
        val streamed = (call.body as TextContent).text.contains("\"stream\":true")
        if (streamed) {
            respond(sse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        } else {
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private suspend fun assertStreamMatches(
        json: String,
        sse: String,
        newProvider: (HttpClientEngine) -> AbstractAiProvider,
    ) {
        val engine = engine(json, sse)
        val plain = newProvider(engine)
        val streamed = newProvider(engine).also { it.configure(apiKey = "k", baseUrl = null, stream = true) }
        plain.configure(apiKey = "k", baseUrl = null)

        val expected = plain.completeRequest(request).copy(durationMs = 0)
        assertEquals("Hello world", expected.content)
        assertEquals(expected, streamed.completeRequest(request).copy(durationMs = 0))

        val chunks = streamed.streamCompletion(request).toList()
        assertTrue(chunks.size > 1, "a streamed reply comes in several chunks: $chunks")
        assertEquals(expected.content, chunks.joinToString("") { it.text })
        assertEquals(expected.completionTokens, chunks.last { it.completionTokens != null }.completionTokens)

        val oneChunk = plain.streamCompletion(request).toList().single()
        assertEquals(expected.content, oneChunk.text)
        assertEquals(expected.promptTokens, oneChunk.promptTokens)
        plain.shutdown()
        streamed.shutdown()
    }

    @Test
    fun `openai stream returns the non-stream result`() =
        runTest {
            assertStreamMatches(OPENAI_JSON, OPENAI_SSE) {
                OpenAiProvider(timeout = TIMEOUT, maxRetries = 0, httpClientEngine = it)
            }
        }

    @Test
    fun `ollama stream returns the non-stream result`() =
        runTest {
            assertStreamMatches(OPENAI_JSON, OPENAI_SSE) {
                OllamaProvider(timeout = TIMEOUT, maxRetries = 0, httpClientEngine = it)
            }
        }

    @Test
    fun `anthropic stream returns the non-stream result`() =
        runTest {
            assertStreamMatches(ANTHROPIC_JSON, ANTHROPIC_SSE) {
                AnthropicProvider(timeout = TIMEOUT, maxRetries = 0, httpClientEngine = it)
            }
        }

    @Test
    fun `ai stream true reaches the run handle`() =
        runTest {
            val engine = engine(json = OPENAI_JSON, sse = OPENAI_SSE)
            val registry = ModuleRegistryImpl().also {
                it.register(OpenAiProvider(timeout = TIMEOUT, maxRetries = 0, httpClientEngine = engine))
            }
            val session = registry.forRun()
            session.provide(
                AiServiceKeys.AI_CONFIG,
                buildJsonObject {
                    put("provider", "openai")
                    put("model", "m")
                    put("apiKey", "k")
                    put("stream", true)
                },
            )
            val provider = assertNotNull(RunAiProviderSource(session, registry) { TestPluginContext(it) }.provider(null))
            val response = provider.complete(request)
            assertEquals("Hello world", response.content)
            assertEquals(15, response.totalTokens)
            assertTrue((engine.requestHistory.single().body as TextContent).text.contains("\"stream\":true"))
            session.close()
        }

    @Test
    fun `ai provider stub answers from replyFile with usage 0`() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("stub-contract")
            val file = Path(dir, "replies.json")
            TestFiles.writeText(file, """{"default": "canned"}""")
            val registry = ModuleRegistryImpl().also { it.register(StubAiProvider()) }
            val session = registry.forRun()
            session.provide(
                AiServiceKeys.AI_CONFIG,
                buildJsonObject {
                    put("provider", "stub")
                    put("model", "m")
                    put("stub", buildJsonObject { put("replyFile", file.toString()) })
                },
            )
            val provider = assertNotNull(RunAiProviderSource(session, registry) { TestPluginContext(it) }.provider(null))
            val response = provider.complete(request)
            assertEquals("canned", response.content)
            assertEquals(0, response.promptTokens + response.completionTokens + response.totalTokens)
            session.close()
            TestFiles.deleteRecursively(dir)
        }

    @Test
    fun `stub lifecycle is skipped as a fallback and shuts down twice`() =
        runTest {
            val stub = StubAiProvider()
            assertFalse(stub.initialize(TestPluginContext(JsonObject(emptyMap()))))
            assertFalse(stub.requiresApiKey)
            assertEquals("stub", stub.providerId)
            val handle = stub.newHandle(TestPluginContext(JsonObject(mapOf("stub" to JsonPrimitive("x")))))
            assertTrue(handle is StubAiProvider && handle !== stub)
            stub.shutdown()
            stub.shutdown()
        }

    private companion object {
        val TIMEOUT: KarandashDuration = KarandashDuration.ofSeconds(5)

        val OPENAI_JSON =
            """{"id":"c","object":"chat.completion","model":"gpt-x","choices":[{"index":0,
                |"message":{"role":"assistant","content":"Hello world"},"finish_reason":"stop"}],
                |"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,
                |"prompt_tokens_details":{"cached_tokens":3}}}
            """.trimMargin()

        val OPENAI_SSE =
            """
            data: {"model":"gpt-x","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"}}]}

            data: {"model":"gpt-x","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":"stop"}]}

            data: {"model":"gpt-x","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"prompt_tokens_details":{"cached_tokens":3}}}

            data: [DONE]

            """.trimIndent()

        val ANTHROPIC_JSON =
            """{"id":"msg","type":"message","role":"assistant","model":"claude-x","stop_reason":"end_turn",
                |"content":[{"type":"text","text":"Hello world"}],
                |"usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":2,"cache_creation_input_tokens":1}}
            """.trimMargin()

        val ANTHROPIC_SSE =
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg","type":"message","role":"assistant","model":"claude-x","content":[],"usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":2,"cache_creation_input_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: ping
            data: {"type":"ping"}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent()
    }
}
