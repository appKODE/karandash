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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `prompts`, `systemPrompts` and `promptOverrides` each reach the provider request; `prompts` wins a conflict. */
class AiPromptKeysTest {
    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
            |"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    @Test
    fun `prompts reaches the provider request`() =
        runTest {
            val body = requestBody(mapOf("prompts" to obj("risk-scoring", buildJsonObject { put("inline", "FROM-PROMPTS") }))).body
            assertTrue("FROM-PROMPTS" in body, body)
        }

    @Test
    fun `systemPrompts inline reaches the provider request`() =
        runTest {
            val body = requestBody(mapOf("systemPrompts" to obj("risk-scoring", buildJsonObject { put("inline", "FROM-SYSTEM") }))).body
            assertTrue("FROM-SYSTEM" in body, body)
        }

    @Test
    fun `systemPrompts file resolves through the host and reaches the provider request`() =
        runTest {
            val file = buildJsonObject {
                put("file", "prompts/risk.md")
                put("auth", "unused")
            }
            val run = requestBody(mapOf("systemPrompts" to obj("risk-scoring", file)))
            assertTrue("FROM-HOST-FILE prompts/risk.md" in run.body, run.body)
        }

    @Test
    fun `promptOverrides reaches the provider request`() =
        runTest {
            val body = requestBody(mapOf("promptOverrides" to obj("risk-scoring", JsonPrimitive("FROM-OVERRIDES")))).body
            assertTrue("FROM-OVERRIDES" in body, body)
        }

    @Test
    fun `all three keys set - prompts wins and a warn names the winner`() =
        runTest {
            val run = requestBody(
                mapOf(
                    "prompts" to obj("riskScoring", buildJsonObject { put("inline", "FROM-PROMPTS") }),
                    "systemPrompts" to obj("risk-scoring", buildJsonObject { put("inline", "FROM-SYSTEM") }),
                    "promptOverrides" to obj("risk-scoring", JsonPrimitive("FROM-OVERRIDES")),
                ),
            )
            assertTrue("FROM-PROMPTS" in run.body, run.body)
            assertTrue("FROM-SYSTEM" !in run.body && "FROM-OVERRIDES" !in run.body, run.body)
            val conflict = run.warnings.single { "risk-scoring" in it && "system prompt" in it }
            assertTrue(conflict.endsWith("using ai.prompts"), conflict)
            assertTrue("ai.systemPrompts" in conflict && "ai.promptOverrides" in conflict, conflict)
        }

    @Test
    fun `systemPrompts entry the host cannot resolve is skipped with a warn`() =
        runTest {
            val run = requestBody(
                mapOf("systemPrompts" to obj("risk-scoring", buildJsonObject { put("url", "https://unresolvable.test/p.md") })),
            )
            assertTrue("BUILTIN" in run.body, run.body)
            assertTrue(run.warnings.any { "ai.systemPrompts.risk-scoring skipped" in it }, run.warnings.toString())
        }

    private class Run(
        val body: String,
        val warnings: List<String>,
    )

    private fun obj(
        key: String,
        value: JsonElement,
    ) = JsonObject(mapOf(key to value))

    private suspend fun requestBody(promptKeys: Map<String, JsonElement>): Run {
        val engine = MockEngine { _ -> respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val provider = OpenAiProvider(
            apiKey = null,
            baseUrl = "https://ai.test/v1",
            timeout = KarandashDuration.ofSeconds(30),
            maxRetries = 0,
            httpClientEngine = engine,
        )
        val registry = ModuleRegistryImpl().also { it.register(provider) }
        val warnings = mutableListOf<String>()
        val config = JsonObject(
            mapOf(
                "provider" to JsonPrimitive("openai"),
                "apiKey" to JsonPrimitive("k"),
                "baseUrl" to JsonPrimitive("https://ai.test/v1"),
                "model" to JsonPrimitive("m"),
            ) + promptKeys,
        )
        val module = AiRuleModule(withAnalyzer(registry))
        assertTrue(module.initialize(FakeContext(config, warnings)))
        module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))
        module.shutdown()
        assertEquals(1, engine.requestHistory.size)
        return Run(
            engine.requestHistory
                .single()
                .body
                .toByteArray()
                .decodeToString(),
            warnings
        )
    }

    /** Only `risk-scoring`, sending the resolved prompt as the system prompt like the real analyzers do. */
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
                    AiCompletionRequest(prompt = "diff", systemPrompt = context.resolvedPrompt ?: "BUILTIN", model = context.model),
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

    /** Host stand-in: resolves local files to a marker, refuses everything else. */
    private class FakeContext(
        override val pluginConfig: JsonElement,
        private val warnings: MutableList<String>,
    ) : PluginContext {
        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override suspend fun resolveContent(source: ContentSource): String =
            when (source) {
                is ContentSource.Inline -> source.value
                is ContentSource.LocalFile -> "FROM-HOST-FILE ${source.path}"
                else -> error("no host fetch in tests")
            }

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
