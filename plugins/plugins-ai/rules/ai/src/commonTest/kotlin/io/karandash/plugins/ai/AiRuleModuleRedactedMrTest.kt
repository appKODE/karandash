package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/** One [AiRuleModule] instance reused across MRs (watch mode) must redact and send each MR, not the first one. */
class AiRuleModuleRedactedMrTest {
    private val okBody =
        """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,
            |"message":{"role":"assistant","content":"{\"findings\":[]}"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimMargin()

    private fun mr(title: String) =
        MergeRequest(
            id = title,
            iid = 1,
            title = title,
            description = "description of $title",
            sourceBranch = "feature/x",
            targetBranch = "main",
            author = User("u1", "dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = listOf(FileChange("src/$title.kt", ChangeType.MODIFIED, additions = 1, deletions = 0)),
        )

    @Test
    fun `second MR validated by the same module sends its own content`() =
        runTest {
            val bodies = MutableStateFlow(emptyList<String>())
            val warnings = mutableListOf<String>()
            val engine = MockEngine { request ->
                bodies.update { it + (request.body as TextContent).text }
                respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val provider = OpenAiProvider(apiKey = "k", maxRetries = 0, httpClientEngine = engine)
            val base = buildTestRegistry()
            val registry = object : ModuleRegistry by base {
                override fun aiProviders(): List<AiProviderModule> = listOf(provider)

                override fun findAiProvider(providerId: String): AiProviderModule? = provider.takeIf { it.providerId == providerId }
            }
            val context = object : PluginContext {
                override val pluginConfig: JsonElement =
                    mapToJsonElement(
                        mapOf(
                            "provider" to "openai",
                            "apiKey" to "k",
                            "model" to "gpt-4o-mini",
                            // A custom rule calls the provider directly (analyzers need the run's AiService).
                            "customRules" to listOf(mapOf("id" to "watch", "name" to "Watch", "prompt" to "Review.")),
                        ),
                    )

                override fun env(name: String): String? = null

                override fun log(message: String) {}

                override fun warn(message: String) {
                    warnings += message
                }

                override val hostVersion: String = "1.0.0-test"
                override val workingDirectory: String = "/tmp/test"
            }
            val module = AiRuleModule(registry)
            assertTrue(module.initialize(context))

            val first = module.validate(RULE, mr("FIRST-MR-TITLE"), JsonObject(emptyMap()))
            module.validate(RULE, mr("SECOND-MR-TITLE"), JsonObject(emptyMap()))
            val skips = module.skippedRuleReasons()
            module.shutdown()

            assertTrue(
                bodies.value.any { "FIRST-MR-TITLE" in it },
                "setup: first MR never reached the provider: ${bodies.value.size} request(s), " +
                    "findings=$first, skips=$skips, warnings=$warnings"
            )
            assertTrue(bodies.value.any { "SECOND-MR-TITLE" in it }, "second MR was answered from the first MR's redacted copy")
        }

    private companion object {
        const val RULE = "ai-watch"
    }
}
