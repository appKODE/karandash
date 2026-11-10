@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.duration.KarandashDuration
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.karandash.sdk.content.ContentSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ai.temperatures` reaches the provider request of the rule it names, and only that rule. */
class AiRuleTemperatureTest {
    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
            |"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    @Test
    fun `ai temperatures entry overrides the analyzer temperature for that rule`() =
        runTest {
            val body = requestBody(mapOf("temperatures" to JsonObject(mapOf("riskScoring" to JsonPrimitive(0.9)))))
            assertTrue("\"temperature\":0.9" in body, body)
        }

    @Test
    fun `a rule without a temperatures entry keeps the analyzer temperature`() =
        runTest {
            val body = requestBody(mapOf("temperatures" to JsonObject(mapOf("reviewComments" to JsonPrimitive(0.9)))))
            assertTrue("\"temperature\":$ANALYZER_TEMPERATURE" in body, body)
        }

    private suspend fun requestBody(extraKeys: Map<String, JsonElement>): String {
        val engine = MockEngine { _ -> respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val provider = OpenAiProvider(
            apiKey = null,
            baseUrl = "https://ai.test/v1",
            timeout = KarandashDuration.ofSeconds(30),
            maxRetries = 0,
            httpClientEngine = engine,
        )
        val registry = ModuleRegistryImpl().also { it.register(provider) }
        val config = JsonObject(
            mapOf(
                "provider" to JsonPrimitive("openai"),
                "apiKey" to JsonPrimitive("k"),
                "baseUrl" to JsonPrimitive("https://ai.test/v1"),
                "model" to JsonPrimitive("m"),
            ) + extraKeys,
        )
        val module = AiRuleModule(withAnalyzer(registry))
        assertTrue(module.initialize(FakeContext(config)))
        module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))
        module.shutdown()
        assertEquals(1, engine.requestHistory.size)
        return engine.requestHistory
            .single()
            .body
            .toByteArray()
            .decodeToString()
    }

    /** Only `risk-scoring`, sending the analyzer's own temperature like the real analyzers do. */
    private fun withAnalyzer(base: ModuleRegistry): ModuleRegistry {
        val analyzer = object : AiAnalyzerModule {
            override val id: String = "risk-scoring"
            override val name: String = "risk-scoring"
            override val version: String = "test"
            override val type: ModuleType get() = ModuleType.AI_ANALYZER
            override val analyzerId: String = "risk-scoring"
            override val featureKey: String = "riskScoring"
            override val defaultEnabled: Boolean = true
            override val needsScopeDetection: Boolean = false

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
                context.aiService.complete(
                    AiCompletionRequest(prompt = "diff", model = context.model, temperature = ANALYZER_TEMPERATURE),
                )
                return emptyList()
            }
        }
        return object : ModuleRegistry by base {
            override fun aiAnalyzers() = listOf(analyzer)

            override fun findAiAnalyzer(analyzerId: String) = analyzer.takeIf { it.analyzerId == analyzerId }
        }
    }

    private fun makeMr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test PR",
            description = "Some description",
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User("u1", "dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = listOf(FileChange("src/Main.kt", ChangeType.MODIFIED, additions = 1, deletions = 0)),
        )

    private class FakeContext(
        override val pluginConfig: JsonElement,
    ) : PluginContext {
        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override suspend fun resolveContent(source: ContentSource): String = error("no host fetch in tests")

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }

    private companion object {
        const val ANALYZER_TEMPERATURE = 0.15
    }
}
