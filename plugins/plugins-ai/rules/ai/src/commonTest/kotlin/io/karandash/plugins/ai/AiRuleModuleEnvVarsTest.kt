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
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The documented AI env vars change what reaches the provider (MockEngine-backed OpenAI provider). */
class AiRuleModuleEnvVarsTest {
    private val mr =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Dry run MR",
            description = "description",
            sourceBranch = "feature/x",
            targetBranch = "main",
            author = User("u1", "dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED, additions = 1, deletions = 0)),
        )

    private class Harness(
        env: Map<String, String>
    ) {
        val requests = MutableStateFlow(0)
        private val provider = OpenAiProvider(
            apiKey = "k",
            maxRetries = 0,
            httpClientEngine = MockEngine {
                requests.update { it + 1 }
                respond(OK_BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        private val base = buildTestRegistry()
        val module = AiRuleModule(
            object : ModuleRegistry by base {
                override fun aiProviders(): List<AiProviderModule> = listOf(provider)

                override fun findAiProvider(providerId: String): AiProviderModule? = provider.takeIf { it.providerId == providerId }
            },
        )
        val context = object : PluginContext {
            override val pluginConfig: JsonElement =
                mapToJsonElement(
                    mapOf(
                        "provider" to "openai",
                        "apiKey" to "k",
                        "model" to "gpt-4o-mini",
                        "customRules" to listOf(mapOf("id" to "dry", "name" to "Dry", "prompt" to "Review.")),
                    ),
                )

            override fun env(name: String): String? = env[name]

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }
    }

    @Test
    fun `dry run env var builds the prompt and reports it without calling the provider`() =
        runTest {
            val h = Harness(mapOf("KARANDASH_AI_DRY_RUN" to "true"))
            assertTrue(h.module.initialize(h.context))

            val findings = h.module.validate(RULE, mr, JsonObject(emptyMap()))
            h.module.shutdown()

            assertEquals(0, h.requests.value, "dry run must not reach the provider")
            val finding = findings.single()
            assertEquals(Severity.INFO, finding.severity)
            assertTrue(finding.message.startsWith("[DRY RUN] AI analysis skipped. Provider: openai, Model: gpt-4o-mini"), finding.message)
            assertTrue(Regex("""Would send 1 request\(s\), [1-9]\d* chars""").containsMatchIn(finding.message), finding.message)
        }

    @Test
    fun `max token budget env var is a hard cap on provider calls`() =
        runTest {
            val unlimited = Harness(emptyMap())
            assertTrue(unlimited.module.initialize(unlimited.context))
            unlimited.module.validate(RULE, mr, JsonObject(emptyMap()))
            unlimited.module.shutdown()
            assertEquals(1, unlimited.requests.value, "setup: without the cap the custom rule calls the provider")

            val capped = Harness(mapOf("KARANDASH_AI_MAX_TOKEN_BUDGET" to "1"))
            assertTrue(capped.module.initialize(capped.context))
            capped.module.validate(RULE, mr, JsonObject(emptyMap()))
            capped.module.shutdown()
            assertEquals(0, capped.requests.value, "a 1-token budget must stop the call before it is sent")
        }

    private companion object {
        const val RULE = "ai-dry"
        const val OK_BODY =
            """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,""" +
                """"message":{"role":"assistant","content":"[]"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
    }
}
