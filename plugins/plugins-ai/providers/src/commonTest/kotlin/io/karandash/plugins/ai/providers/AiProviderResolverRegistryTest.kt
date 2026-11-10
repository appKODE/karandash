package io.karandash.plugins.ai.providers

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.duration.KarandashDuration
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiProviderResolverRegistryTest {
    private class StubProvider(
        override val providerId: String,
        private val initOk: Boolean = true,
        override val apiKeyEnvVar: String? = null,
        override val baseUrlEnvVar: String? = null,
    ) : AiProviderModule {
        val requests = mutableListOf<AiCompletionRequest>()
        override val id: String = "stub.$providerId"
        override val name: String = providerId
        override val version: String = "1"

        override suspend fun initialize(context: PluginContext): Boolean = initOk

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
            requests += request
            return AiCompletionResponse("ok", 1, 2, 3, request.model, cacheReadTokens = 4)
        }
    }

    private fun registry(vararg providers: AiProviderModule): ModuleRegistry =
        object : ModuleRegistry {
            override suspend fun register(module: Module) = Unit

            override suspend fun unregister(id: String) = false

            override fun get(id: String): Module? = null

            override fun getByType(type: ModuleType): List<Module> = if (type == ModuleType.AI_PROVIDER) providers.toList() else emptyList()

            override fun registeredIds(): Set<String> = emptySet()

            override fun hasRule(ruleId: String) = false

            override fun findRuleHandler(ruleId: String): RuleModule? = null

            override fun findByAlias(alias: String): PlatformServiceModule? = null

            override fun allAliases(): Map<String, PlatformServiceModule> = emptyMap()

            override fun aliasCollisions(): List<String> = emptyList()
        }

    private class Ctx(
        override val pluginConfig: JsonElement = JsonObject(emptyMap()),
    ) : PluginContext {
        val warnings = mutableListOf<String>()

        override fun env(name: String): String? = null

        override fun log(message: String) {}

        override fun warn(message: String) {
            warnings += message
        }

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }

    @Test
    fun `registry provider receives config baseUrl and apiKey before init`() =
        runTest {
            val urls = mutableListOf<String>()
            val auth = mutableListOf<String?>()
            val engine = MockEngine { request ->
                urls += request.url.toString()
                auth += request.headers[HttpHeaders.Authorization]
                respond(
                    """{"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"}}],""" +
                        """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val openai = OpenAiProvider(apiKey = null, timeout = KarandashDuration.ofSeconds(5), maxRetries = 0, httpClientEngine = engine)
            val ctx = Ctx(
                buildJsonObject {
                    put("baseUrl", "https://llm.example.com/v1")
                    put("timeout", "1500")
                    put("maxRetries", 1)
                    put("structuredOutput", false)
                },
            )

            val provider = assertNotNull(AiProviderResolver(registry(openai)).resolve("OpenAI", "sk-config", ctx))
            provider.complete(prompt = "p", model = "gpt-4o-mini", maxTokens = 10, temperature = 0.1)
            openai.close()

            assertEquals("https://llm.example.com/v1/chat/completions", urls.single())
            assertEquals("Bearer sk-config", auth.single())
            assertTrue(ctx.warnings.isEmpty(), ctx.warnings.toString())
        }

    @Test
    fun `a provider resolved outside a run sends ai reasoningEffort like a run handle`() =
        runTest {
            val bodies = mutableListOf<String>()
            val engine = MockEngine { request ->
                bodies += (request.body as TextContent).text
                respond(
                    """{"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"}}],""" +
                        """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val openai = OpenAiProvider(apiKey = null, timeout = KarandashDuration.ofSeconds(5), maxRetries = 0, httpClientEngine = engine)
            val ctx = Ctx(
                buildJsonObject {
                    put("baseUrl", "https://llm.example.com/v1")
                    put("reasoningEffort", "low")
                },
            )

            val provider = assertNotNull(AiProviderResolver(registry(openai)).resolve("openai", "sk", ctx))
            provider.complete(prompt = "p", model = "m", maxTokens = 10, temperature = 0.1)
            openai.close()

            assertTrue("\"reasoning\":{\"effort\":\"low\"}" in bodies.single(), bodies.single())
        }

    @Test
    fun `failed registry init warns with the required settings`() =
        runTest {
            val withEnv = StubProvider("acme", initOk = false, apiKeyEnvVar = "ACME_KEY", baseUrlEnvVar = "ACME_URL")
            val bare = StubProvider("bare", initOk = false)
            val resolver = AiProviderResolver(registry(withEnv, bare))
            val ctx = Ctx()

            assertNull(resolver.resolve("acme", null, ctx))
            assertNull(resolver.resolve("bare", null, ctx))

            assertEquals(
                listOf(
                    "AI plugin: provider 'acme' failed to initialize. Required: ACME_KEY (or ai.apiKey), ACME_URL (or ai.baseUrl).",
                    "AI plugin: provider 'bare' failed to initialize. " +
                        "No required environment variables are declared; check the provider's endpoint.",
                ),
                ctx.warnings,
            )
        }

    @Test
    fun `unknown provider lists registry ids and custom in order`() {
        val message = AiProviderResolver(registry(StubProvider("zeta"), StubProvider("alpha"))).unresolvedMessage("Nope", false)

        assertEquals("AI plugin: unknown AI provider 'nope'. Known providers: alpha, custom, zeta.", message)
        assertEquals("AI plugin: Custom provider failed to initialize.", AiProviderResolver().unresolvedMessage("custom", true))
    }

    @Test
    fun `module without AiProvider is wrapped and folds context blocks into the prompt`() =
        runTest {
            val stub = StubProvider("plain")
            val provider = assertNotNull(AiProviderResolver(registry(stub)).resolveNamed("PLAIN", Ctx()))

            val response = provider.completeRequest(
                AiCompletionRequest(prompt = "question", model = "m", contextBlocks = listOf("ctx")),
            )
            provider.complete("p2", "sys", "m2", 5, 0.3, jsonMode = true)
            provider.close()

            assertEquals("plain", provider.providerId)
            assertEquals("ctx\n\nquestion", stub.requests[0].prompt)
            assertTrue(stub.requests[0].contextBlocks.isEmpty())
            assertEquals(4, response.cacheReadTokens)
            assertEquals(3, response.totalTokens)
            assertEquals("sys", stub.requests[1].systemPrompt)
        }

    @Test
    fun `resolveNamed returns null for a missing or failing provider`() =
        runTest {
            val resolver = AiProviderResolver(registry(StubProvider("down", initOk = false)))

            assertNull(resolver.resolveNamed("down", Ctx()))
            assertNull(resolver.resolveNamed("absent", Ctx()))
            assertNull(AiProviderResolver().resolveNamed("down", Ctx()))
        }
}
