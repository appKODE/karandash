package io.karandash.plugins.ai.providers

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.cache.TieredCache
import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Schema-constrained output and prompt-cache metering across the providers and wrappers. */
class StructuredOutputProviderTest {
    private val schema = KarandashJson.Default.parseToJsonElement(
        """{"type":"object","properties":{"comments":{"type":"array"}},"required":["comments"]}""",
    )

    private fun anthropicBody(content: String) =
        """
        {"id":"m","type":"message","role":"assistant","model":"claude-x","stop_reason":"tool_use",
         "content":$content,
         "usage":{"input_tokens":5,"output_tokens":7,"cache_creation_input_tokens":100,"cache_read_input_tokens":40}}
        """.trimIndent()

    private fun recordingEngine(
        sent: MutableList<String>,
        body: String,
    ) = MockEngine { request ->
        sent += request.body.toByteArray().decodeToString()
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun anthropic(engine: MockEngine) =
        AnthropicProvider(apiKey = "k", timeout = KarandashDuration.ofSeconds(5), maxRetries = 0, httpClientEngine = engine)

    private fun request(schema: JsonElement? = this.schema) =
        AiCompletionRequest(prompt = "diff", systemPrompt = "Review it", model = "claude-x", responseSchema = schema)

    @Test
    fun `anthropic marks the system prompt as an ephemeral cache breakpoint`() =
        runTest {
            val sent = mutableListOf<String>()
            val provider = anthropic(recordingEngine(sent, anthropicBody("""[{"type":"text","text":"ok"}]""")))

            provider.complete(prompt = "diff", systemPrompt = "Review it")

            val system = KarandashJson.Default
                .parseToJsonElement(sent.single())
                .jsonObject
                .getValue("system")
                .jsonArray
            val block = system.single().jsonObject
            assertEquals("Review it", block.getValue("text").jsonPrimitive.content)
            assertEquals(
                "ephemeral",
                block
                    .getValue("cache_control")
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content
            )
            assertFalse("ttl" in block.getValue("cache_control").jsonObject, "a prototype sends the 5-minute default")
        }

    private fun userContent(body: String) =
        KarandashJson.Default
            .parseToJsonElement(body)
            .jsonObject
            .getValue("messages")
            .jsonArray
            .single()
            .jsonObject
            .getValue("content")

    private fun breakpoints(body: String) = Regex("\"cache_control\"").findAll(body).count()

    @Test
    fun `anthropic sends context blocks ahead of the diff and marks the last one as a cache breakpoint`() =
        runTest {
            val sent = mutableListOf<String>()
            val provider = anthropic(recordingEngine(sent, anthropicBody("""[{"type":"tool_use","name":"respond","input":{}}]""")))

            provider.completeRequest(request().copy(contextBlocks = listOf("## Merge Request Metadata")))

            val blocks = userContent(sent.single()).jsonArray.map { it.jsonObject }
            assertEquals(listOf("## Merge Request Metadata", "diff"), blocks.map { it.getValue("text").jsonPrimitive.content })
            assertEquals(
                "ephemeral",
                blocks[0]
                    .getValue("cache_control")
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content,
            )
            assertFalse("cache_control" in blocks[1], "the diff varies, so it is never a breakpoint")
            assertEquals(2, breakpoints(sent.single()), "system prompt + context block")
        }

    @Test
    fun `anthropic never sends more than four cache breakpoints`() =
        runTest {
            val sent = mutableListOf<String>()
            val provider = anthropic(recordingEngine(sent, anthropicBody("""[{"type":"text","text":"ok"}]""")))

            provider.completeRequest(request(schema = null).copy(contextBlocks = (1..5).map { "context $it" }))

            val blocks = userContent(sent.single()).jsonArray.map { it.jsonObject }
            assertEquals(4, breakpoints(sent.single()))
            assertEquals(listOf(false, false, true, true, true, false), blocks.map { "cache_control" in it })
        }

    @Test
    fun `anthropic keeps a plain string user turn without context blocks`() =
        runTest {
            val sent = mutableListOf<String>()
            val provider = anthropic(recordingEngine(sent, anthropicBody("""[{"type":"text","text":"ok"}]""")))

            provider.completeRequest(request(schema = null))

            assertEquals("diff", userContent(sent.single()).jsonPrimitive.content)
            assertEquals(1, breakpoints(sent.single()))
        }

    @Test
    fun `a provider without native blocks sends them inside the prompt`() =
        runTest {
            val sent = mutableListOf<String>()
            val body = """{"model":"gpt","choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
            val openAi = OpenAiProvider(apiKey = "k", maxRetries = 0, httpClientEngine = recordingEngine(sent, body))

            openAi.completeRequest(request(schema = null).copy(contextBlocks = listOf("meta")))

            assertContains(sent.single(), "meta\\n\\ndiff")
        }

    @Test
    fun `anthropic usage counts cached input and reports cache reads and writes`() =
        runTest {
            val provider = anthropic(recordingEngine(mutableListOf(), anthropicBody("""[{"type":"text","text":"ok"}]""")))

            val response = provider.completeSdk(request(schema = null))

            assertEquals(145, response.promptTokens)
            assertEquals(152, response.totalTokens)
            assertEquals(40, response.cacheReadTokens)
            assertEquals(100, response.cacheWriteTokens)
        }

    @Test
    fun `anthropic structured request forces the schema tool and returns its input`() =
        runTest {
            val sent = mutableListOf<String>()
            val toolUse = """[{"type":"tool_use","id":"t","name":"respond","input":{"comments":[]}}]"""
            val provider = anthropic(recordingEngine(sent, anthropicBody(toolUse)))

            val response = provider.completeSdk(request())

            val body = KarandashJson.Default.parseToJsonElement(sent.single()).jsonObject
            val tool = body
                .getValue("tools")
                .jsonArray
                .single()
                .jsonObject
            assertEquals(schema, tool.getValue("input_schema"))
            assertEquals(
                "respond",
                body
                    .getValue("tool_choice")
                    .jsonObject
                    .getValue("name")
                    .jsonPrimitive.content
            )
            assertEquals("""{"comments":[]}""", response.content)
        }

    @Test
    fun `openai structured request sends a json_schema response format`() =
        runTest {
            val sent = mutableListOf<String>()
            val body =
                """{"model":"gpt","choices":[{"message":{"role":"assistant","content":"{}"}}],
                   "usage":{"prompt_tokens":9,"completion_tokens":1,"total_tokens":10,"prompt_tokens_details":{"cached_tokens":4}}}"""
            val provider = OpenAiProvider(apiKey = "k", maxRetries = 0, httpClientEngine = recordingEngine(sent, body))

            val response = provider.completeSdk(request())

            val format = KarandashJson.Default
                .parseToJsonElement(sent.single())
                .jsonObject
                .getValue("response_format")
                .jsonObject
            assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
            assertEquals(schema, format.getValue("json_schema").jsonObject.getValue("schema"))
            assertEquals(4, response.cacheReadTokens)
        }

    private fun sentJsonSchema(body: String) =
        KarandashJson.Default
            .parseToJsonElement(body)
            .jsonObject
            .getValue("response_format")
            .jsonObject
            .getValue("json_schema")
            .jsonObject

    @Test
    fun `openai sends a strict schema with every property required and optional ones nullable`() =
        runTest {
            val sent = mutableListOf<String>()
            val body = """{"model":"gpt","choices":[{"message":{"role":"assistant","content":"{\"comments\":[],\"note\":null}"}}]}"""
            val provider = OpenAiProvider(apiKey = "k", maxRetries = 0, httpClientEngine = recordingEngine(sent, body))
            val optional = KarandashJson.Default.parseToJsonElement(
                """{"type":"object","properties":{"comments":{"type":"array","items":{"type":"string"}},
                   "note":{"type":"string"}},"required":["comments"]}""",
            )

            val response = provider.completeSdk(request(schema = optional))

            val jsonSchema = sentJsonSchema(sent.single())
            assertEquals("true", jsonSchema.getValue("strict").jsonPrimitive.content)
            val sentSchema = jsonSchema.getValue("schema").jsonObject
            assertEquals("false", sentSchema.getValue("additionalProperties").jsonPrimitive.content)
            assertEquals(listOf("comments", "note"), sentSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
            val note = sentSchema
                .getValue("properties")
                .jsonObject
                .getValue("note")
                .jsonObject
            assertEquals(2, note.getValue("anyOf").jsonArray.size, "an optional field may be null: $note")
            assertEquals("""{"comments":[]}""", response.content, "the nulls strict mode forces are dropped again")
        }

    @Test
    fun `openai keeps a schema with an open object non-strict`() =
        runTest {
            val sent = mutableListOf<String>()
            val body = """{"model":"gpt","choices":[{"message":{"role":"assistant","content":"{}"}}]}"""
            val provider = OpenAiProvider(apiKey = "k", maxRetries = 0, httpClientEngine = recordingEngine(sent, body))
            val open = KarandashJson.Default.parseToJsonElement(
                """{"type":"object","properties":{"meta":{"type":"object"}},"required":["meta"]}""",
            )

            provider.completeSdk(request(schema = open))

            val jsonSchema = sentJsonSchema(sent.single())
            assertEquals("false", jsonSchema.getValue("strict").jsonPrimitive.content)
            assertEquals(open, jsonSchema.getValue("schema"))
        }

    private fun compatible(
        sent: MutableList<String>,
        baseUrl: String = "https://llm.internal.example/v1",
    ) = OpenAiProvider(
        apiKey = "k",
        baseUrl = baseUrl,
        maxRetries = 0,
        httpClientEngine = recordingEngine(sent, """{"model":"gpt","choices":[{"message":{"role":"assistant","content":"{}"}}]}"""),
    )

    @Test
    fun `an openai-compatible host with structuredOutput unset still asks for a json_object reply`() =
        runTest {
            val sent = mutableListOf<String>()

            compatible(sent).completeSdk(request())

            val format = KarandashJson.Default
                .parseToJsonElement(sent.single())
                .jsonObject
                .getValue("response_format")
                .jsonObject
            assertEquals("json_object", format.getValue("type").jsonPrimitive.content)
        }

    @Test
    fun `a host that only contains api_openai_com is not treated as openai`() =
        runTest {
            val sent = mutableListOf<String>()

            compatible(sent, baseUrl = "https://api.openai.com.evil.example/v1").completeSdk(request())

            val format = KarandashJson.Default
                .parseToJsonElement(sent.single())
                .jsonObject
                .getValue("response_format")
                .jsonObject
            assertEquals("json_object", format.getValue("type").jsonPrimitive.content)
        }

    @Test
    fun `an openai-compatible host with structuredOutput true gets the schema non-strict`() =
        runTest {
            val sent = mutableListOf<String>()
            val provider = compatible(sent)
            provider.configure(apiKey = null, baseUrl = null, structuredOutput = true)

            provider.completeSdk(request())

            val jsonSchema = sentJsonSchema(sent.single())
            assertEquals("false", jsonSchema.getValue("strict").jsonPrimitive.content)
            assertEquals(schema, jsonSchema.getValue("schema"))
        }

    private fun handleContext(config: JsonElement) =
        object : PluginContext {
            override val pluginConfig = config
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = null
        }

    @Test
    fun `a run handle reads structuredOutput true from its plugin config`() =
        runTest {
            val sent = mutableListOf<String>()
            val handle = compatible(sent).newHandle(
                handleContext(KarandashJson.Default.parseToJsonElement("""{"structuredOutput": true}""")),
            ) as OpenAiProvider

            handle.completeSdk(request())

            assertEquals("false", sentJsonSchema(sent.single()).getValue("strict").jsonPrimitive.content)
        }

    @Test
    fun `openai json mode keeps the plain json_object format`() =
        runTest {
            val sent = mutableListOf<String>()
            val body = """{"model":"gpt","choices":[{"message":{"role":"assistant","content":"{}"}}]}"""
            val provider = OpenAiProvider(apiKey = "k", maxRetries = 0, httpClientEngine = recordingEngine(sent, body))

            provider.complete(prompt = "p", jsonMode = true)

            val format = KarandashJson.Default
                .parseToJsonElement(sent.single())
                .jsonObject
                .getValue("response_format")
                .jsonObject
            assertEquals("json_object", format.getValue("type").jsonPrimitive.content)
            assertFalse("json_schema" in format)
        }

    @Test
    fun `a provider without native support degrades a schema to json mode`() =
        runTest {
            val seen = mutableListOf<Boolean>()
            val plain = object : AiProvider {
                override val providerId = "plain"

                override suspend fun complete(
                    prompt: String,
                    systemPrompt: String?,
                    model: String,
                    maxTokens: Int,
                    temperature: Double,
                    jsonMode: Boolean,
                ): AiResponse {
                    seen += jsonMode
                    return AiResponse("{}", 1, 1, 2, model)
                }

                override suspend fun close() = Unit
            }

            plain.completeSdk(request())

            assertEquals(listOf(true), seen)
        }

    @Test
    fun `fallback and cache wrappers keep the schema and key on it`() =
        runTest {
            val schemas = mutableListOf<JsonElement?>()
            val inner = object : AiProvider {
                override val providerId = "inner"

                override suspend fun complete(
                    prompt: String,
                    systemPrompt: String?,
                    model: String,
                    maxTokens: Int,
                    temperature: Double,
                    jsonMode: Boolean,
                ): AiResponse = AiResponse("{}", 1, 1, 2, model)

                override suspend fun completeRequest(request: AiCompletionRequest): AiResponse {
                    schemas += request.responseSchema
                    return AiResponse("{}", 1, 1, 2, request.model)
                }

                override suspend fun close() = Unit
            }
            val cache = TieredCache<String, AiResponse>(name = "t", maxEntries = 10)
            val chain = CachingAiProvider(FallbackAiProvider(inner, emptyList()) { _, _ -> }, cache)

            chain.completeSdk(request())
            chain.completeSdk(request())
            chain.completeSdk(request(schema = null))

            // the repeated schema call is a cache hit; the schema-less one is a separate key
            assertEquals(listOf(schema, null), schemas)
        }
}
