@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.providers

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiServiceKeys
import io.karandash.api.run.RunSession
import io.karandash.duration.KarandashDuration
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.plugins.ai.core.AiEnvVars
import io.karandash.plugins.ai.core.ReasoningEffortOverride
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class RunAiProviderSourceTest {
    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
            |"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    private val request = AiCompletionRequest(prompt = "hi", model = "m")

    private fun engine(status: HttpStatusCode = HttpStatusCode.OK) =
        MockEngine { _ ->
            respond(okBody, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    private suspend fun registryWith(engine: MockEngine): ModuleRegistryImpl =
        ModuleRegistryImpl().also {
            it.register(
                OpenAiProvider(
                    apiKey = null,
                    baseUrl = "https://proto.test/v1",
                    timeout = KarandashDuration.ofSeconds(30),
                    maxRetries = 2,
                    httpClientEngine = engine,
                ),
            )
        }

    private fun aiConfig(vararg pairs: Pair<String, Any>): JsonObject =
        JsonObject(
            mapOf("provider" to JsonPrimitive("openai"), "model" to JsonPrimitive("m")) +
                pairs.associate { (k, v) -> k to if (v is Int) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
        )

    private fun source(
        session: RunSession,
        registry: ModuleRegistryImpl,
    ) = RunAiProviderSource(session, registry) { FakeContext(it) }

    @Test
    fun `using the source before the run publishes its AI config fails loudly`() =
        runTest {
            val registry = registryWith(engine())
            val session = registry.forRun()
            assertFailsWith<IllegalStateException> { source(session, registry).provider(null) }
            session.close()
        }

    @Test
    fun `main provider gets ai apiKey and baseUrl and identical requests hit the network once`() =
        runTest {
            val engine = engine()
            val registry = registryWith(engine)
            val session = registry.forRun()
            session.provide(AiServiceKeys.AI_CONFIG, aiConfig("apiKey" to "secret", "baseUrl" to "https://run.test/v1"))
            val provider = assertNotNull(source(session, registry).provider(null))
            repeat(2) { assertEquals("ok", provider.complete(request).content) }
            assertEquals(1, engine.requestHistory.size)
            val sent = engine.requestHistory.single()
            assertEquals("run.test", sent.url.host)
            assertEquals("Bearer secret", sent.headers[HttpHeaders.Authorization])
            session.close()
        }

    @Test
    fun `ai promptCacheTtl 1h reaches the anthropic cache breakpoint`() =
        runTest {
            val body =
                """{"id":"m","type":"message","role":"assistant","model":"claude-x","stop_reason":"end_turn",
                    |"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":1,"output_tokens":1}}
                """.trimMargin()
            val engine = MockEngine { _ -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val registry = ModuleRegistryImpl().also {
                it.register(AnthropicProvider(timeout = KarandashDuration.ofSeconds(5), maxRetries = 0, httpClientEngine = engine))
            }
            val session = registry.forRun()
            session.provide(
                AiServiceKeys.AI_CONFIG,
                aiConfig("provider" to "anthropic", "apiKey" to "k", "promptCacheTtl" to "1h"),
            )
            val provider = assertNotNull(source(session, registry).provider(null))
            provider.complete(AiCompletionRequest(prompt = "diff", systemPrompt = "Review it", model = "claude-x"))
            val sent = (engine.requestHistory.single().body as TextContent).text
            assertContains(sent, "\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}")
            session.close()
        }

    @Test
    fun `ai structuredOutput true reaches the run handle of an openai-compatible host`() =
        runTest {
            val engine = engine()
            val registry = registryWith(engine)
            val session = registry.forRun()
            session.provide(AiServiceKeys.AI_CONFIG, aiConfig("apiKey" to "k", "structuredOutput" to true))
            val provider = assertNotNull(source(session, registry).provider(null))
            val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
            provider.complete(request.copy(responseSchema = schema))
            assertContains((engine.requestHistory.single().body as TextContent).text, "\"json_schema\"")
            session.close()
        }

    private suspend fun streamedCallWarns(
        engine: MockEngine,
        vararg limits: Pair<String, String>,
    ): List<String> {
        val logger = RecordingLogger()
        val registry = registryWith(engine)
        val session = registry.forRun()
        val config =
            aiConfig("apiKey" to "k", "maxRetries" to 1).let { base ->
                JsonObject(base + ("stream" to JsonPrimitive(true)) + limits.associate { (k, v) -> k to JsonPrimitive(v) })
            }
        session.provide(AiServiceKeys.AI_CONFIG, config)
        val provider = assertNotNull(RunAiProviderSource(session, registry) { FakeContext(it, logger = logger) }.provider(null))
        assertFailsWith<AiProviderException> { provider.complete(request) }
        session.close()
        return logger.lines.filter { it.startsWith("WARN") && "[ai]" in it && "streamed reply" in it }
    }

    @Test
    fun `ai streamDeadline bounds a streamed call that never finishes and the call is retried`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    delay(STREAM_HANG_MS)
                    respond(okBody, HttpStatusCode.OK)
                }

            val warns = streamedCallWarns(engine, "streamDeadline" to "200ms")

            assertEquals(2, warns.size, warns.joinToString("\n"))
            assertContains(warns.first(), "total deadline of 200ms")
            assertContains(warns.first(), "ai.streamDeadline")
        }

    @Test
    fun `ai streamStall turns a silent stream into a retried call that names the key`() =
        runTest {
            val engine = MockEngine { _ -> throw SocketTimeoutException("read timed out") }

            val warns = streamedCallWarns(engine, "streamStall" to "5s")

            assertEquals(2, warns.size, warns.joinToString("\n"))
            assertContains(warns.first(), "sent no chunk for 5s")
            assertContains(warns.first(), "ai.streamStall")
        }

    @Test
    fun `ai reasoningEffort is sent as the reasoning object only when set`() =
        runTest {
            val engine = engine()
            val registry = registryWith(engine)
            val session = registry.forRun()
            session.provide(AiServiceKeys.AI_CONFIG, aiConfig("apiKey" to "k", "reasoningEffort" to "low"))
            assertNotNull(source(session, registry).provider(null)).complete(request)
            assertContains((engine.requestHistory.single().body as TextContent).text, "\"reasoning\":{\"effort\":\"low\"}")
            session.close()

            val plain = engine()
            val plainRegistry = registryWith(plain)
            val plainSession = plainRegistry.forRun()
            plainSession.provide(AiServiceKeys.AI_CONFIG, aiConfig("apiKey" to "k"))
            assertNotNull(source(plainSession, plainRegistry).provider(null)).complete(request)
            assertFalse("reasoning" in (plain.requestHistory.single().body as TextContent).text)
            plainSession.close()
        }

    @Test
    fun `a reasoning effort override is sent over ai reasoningEffort and never answered from the cache`() =
        runTest {
            val engine = engine()
            val registry = registryWith(engine)
            val session = registry.forRun()
            session.provide(AiServiceKeys.AI_CONFIG, aiConfig("apiKey" to "k", "reasoningEffort" to "high"))
            val provider = assertNotNull(source(session, registry).provider(null))
            provider.complete(request)
            ReasoningEffortOverride.with(ReasoningEffortOverride.NONE) { provider.complete(request) }
            val sent = engine.requestHistory.map { (it.body as TextContent).text }
            assertEquals(2, sent.size)
            assertContains(sent[0], "\"reasoning\":{\"effort\":\"high\"}")
            assertContains(sent[1], "\"reasoning\":{\"effort\":\"none\"}")
            session.close()
        }

    @Test
    fun `maxRetries 0 still makes one attempt`() =
        runTest {
            val engine = engine(HttpStatusCode.ServiceUnavailable)
            val registry = registryWith(engine)
            val session = registry.forRun()
            session.provide(AiServiceKeys.AI_CONFIG, aiConfig("apiKey" to "k", "maxRetries" to 0))
            val provider = assertNotNull(source(session, registry).provider(null))
            assertFailsWith<AiProviderException> { provider.complete(request) }
            assertEquals(1, engine.requestHistory.size)
            session.close()
        }

    @Test
    fun `a custom provider sends ai reasoningEffort like a registered one`() =
        runTest {
            val engine = engine()
            val registry = registryWith(engine())
            val session = registry.forRun()
            session.provide(
                AiServiceKeys.AI_CONFIG,
                aiConfig("provider" to "custom", "baseUrl" to "https://custom.test/v1", "reasoningEffort" to "low"),
            )
            val source = RunAiProviderSource(session, registry, customEngine = engine) { FakeContext(it) }
            assertNotNull(source.provider(null)).complete(request)
            val sent = engine.requestHistory.single()
            assertEquals("custom.test", sent.url.host)
            assertContains((sent.body as TextContent).text, "\"reasoning\":{\"effort\":\"low\"}")
            session.close()
        }

    @Test
    fun `custom provider is built from the run config and memoized`() =
        runTest {
            val registry = registryWith(engine())
            val session = registry.forRun()
            session.provide(
                AiServiceKeys.AI_CONFIG,
                aiConfig("provider" to "custom", "apiKey" to "k", "baseUrl" to "https://custom.test/v1"),
            )
            val source = source(session, registry)
            val first = assertNotNull(source.provider(null))
            assertSame(first, source.provider(null))
            session.close()
        }

    @Test
    fun `the deadline warning uses the provider own timeout and retries when ai sets none`() =
        runTest {
            suspend fun warnings(executionTimeout: String): List<String> {
                val registry = registryWith(engine())
                val session = registry.forRun()
                val logger = RecordingLogger()
                session.provide(AiServiceKeys.AI_CONFIG, aiConfig("apiKey" to "k", "executionTimeout" to executionTimeout))
                assertNotNull(RunAiProviderSource(session, registry) { FakeContext(it, logger = logger) }.provider(null))
                session.close()
                return logger.lines.filter { "exceeds ai.executionTimeout" in it }
            }
            // the registered provider waits 30s with 2 retries: 90s in all, not the custom default 60s x 3
            assertEquals(emptyList(), warnings("100s"))
            assertContains(warnings("80s").single(), "= 1m 30s exceeds")
        }

    @Test
    fun `the main provider asked by its id is the same handle as the default and is built once`() =
        runTest {
            val registry = registryWith(engine())
            val session = registry.forRun()
            val logger = RecordingLogger()
            session.provide(AiServiceKeys.AI_CONFIG, aiConfig("apiKey" to "k", "timeout" to "5m", "executionTimeout" to "6m"))
            val source = RunAiProviderSource(session, registry) { FakeContext(it, logger = logger) }
            val first = assertNotNull(source.provider(null))
            assertSame(first, source.provider("OpenAI"))
            assertEquals(1, logger.lines.count { "exceeds ai.executionTimeout" in it }, logger.lines.toString())
            session.close()
        }

    @Test
    fun `concurrent runs with different AI configs stay isolated`() =
        runTest {
            val engine = engine()
            val registry = registryWith(engine)
            val hosts = listOf("a", "b").map { name ->
                async {
                    val session = registry.forRun()
                    session.provide(
                        AiServiceKeys.AI_CONFIG,
                        aiConfig("apiKey" to "key-$name", "baseUrl" to "https://$name.test/v1"),
                    )
                    assertNotNull(source(session, registry).provider(null)).complete(request)
                    session.close()
                }
            }
            hosts.awaitAll()
            val seen = engine.requestHistory.map { it.url.host to it.headers[HttpHeaders.Authorization] }.toSet()
            assertEquals(setOf("a.test" to "Bearer key-a", "b.test" to "Bearer key-b"), seen)
        }

    @Test
    fun `base provider is the unwrapped run handle and closing it leaves the run handle open`() =
        runTest {
            val engine = engine()
            val registry = registryWith(engine)
            val session = registry.forRun()
            session.provide(AiServiceKeys.AI_CONFIG, aiConfig("apiKey" to "secret", "baseUrl" to "https://run.test/v1"))
            val base = assertNotNull(source(session, registry).baseProvider(null))
            repeat(2) { assertEquals("ok", base.complete("hi", null, "m", 10, 0.0, false).content) }
            base.close()
            base.complete("hi", null, "m", 10, 0.0, false)
            assertEquals(3, engine.requestHistory.size, "no cache on the base provider; close() is a no-op")
            assertEquals("Bearer secret", engine.requestHistory.first().headers[HttpHeaders.Authorization])
            session.close()
        }

    @Test
    fun `ai apiKey wins over the provider env var and the env var is the fallback`() =
        runTest {
            suspend fun sentKey(vararg pairs: Pair<String, Any>): String? {
                val engine = engine()
                val registry = registryWith(engine)
                val session = registry.forRun()
                session.provide(AiServiceKeys.AI_CONFIG, aiConfig(*pairs))
                val env = mapOf(AiEnvVars.OPENAI_API_KEY to "env-k")
                assertNotNull(RunAiProviderSource(session, registry) { FakeContext(it, env) }.provider(null)).complete(request)
                session.close()
                return engine.requestHistory.single().headers[HttpHeaders.Authorization]
            }
            assertEquals("Bearer cfg-k", sentKey("apiKey" to "cfg-k"))
            assertEquals("Bearer env-k", sentKey())
        }

    @Test
    fun `named provider keys match case-insensitively and unknown names resolve to null`() =
        runTest {
            val registry = registryWith(engine())
            val session = registry.forRun()
            session.provide(
                AiServiceKeys.AI_CONFIG,
                JsonObject(aiConfig() + ("providers" to JsonObject(mapOf("fastModel" to JsonPrimitive("openai"))))),
            )
            // named providers are env-only, like the resolver's
            val source = RunAiProviderSource(session, registry) { FakeContext(it, mapOf(AiEnvVars.OPENAI_API_KEY to "env")) }
            assertNotNull(source.baseProvider("fastModel"))
            assertNull(source.baseProvider("nope"))
            session.close()
        }

    @Test
    fun `the run AI service asks one model even with consensus models since the vote runs per rule`() =
        runTest {
            val models = "consensusModels" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
            for ((flag, expectedCalls) in listOf(null to 1, true to 1)) {
                val engine = engine()
                val registry = registryWith(engine)
                val session = registry.forRun()
                val extra = flag?.let { mapOf("analyzerConsensus" to JsonPrimitive(it)) }.orEmpty()
                session.provide(AiServiceKeys.AI_CONFIG, JsonObject(aiConfig("apiKey" to "k") + models + extra))
                assertNotNull(source(session, registry).provider(null)).complete(request)
                assertEquals(expectedCalls, engine.requestHistory.size, "analyzerConsensus=$flag")
                session.close()
            }
        }

    @Test
    fun `consensus models without analyzer consensus warn that analyzers use one model`() =
        runTest {
            val models = "consensusModels" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
            for ((flag, expectWarning) in listOf(false to true, true to false)) {
                val registry = registryWith(engine())
                val session = registry.forRun()
                val logger = RecordingLogger()
                val cfg = aiConfig("apiKey" to "k") + models + ("analyzerConsensus" to JsonPrimitive(flag))
                session.provide(AiServiceKeys.AI_CONFIG, JsonObject(cfg))
                RunAiProviderSource(session, registry) { FakeContext(it, logger = logger) }.provider(null)
                assertEquals(
                    expectWarning,
                    logger.lines.any { it.startsWith("WARN [ai] consensusModels is set but analyzerConsensus is false") },
                    "analyzerConsensus=$flag: ${logger.lines}",
                )
                session.close()
            }
        }

    private companion object {
        const val STREAM_HANG_MS = 60_000L
    }

    private class FakeContext(
        override val pluginConfig: JsonElement,
        private val envVars: Map<String, String> = emptyMap(),
        override val logger: Logger = NoOpLogger,
    ) : PluginContext {
        override fun env(name: String): String? = envVars[name]

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
