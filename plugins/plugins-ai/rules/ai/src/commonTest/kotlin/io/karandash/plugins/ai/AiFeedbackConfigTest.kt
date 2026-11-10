package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.karandash.testfixtures.createTestMergeRequest
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

/** `ai.feedback` object form and `skipRepeatFindingTypes` (MockEngine-backed OpenAI provider). */
class AiFeedbackConfigTest {
    @Test
    fun `object form loads and skipRepeatFindingTypes skips a rule dismissed repeatedly`() =
        runTest {
            assertEquals(0, providerCalls(skipRepeat = true, dismissCount = 2))
        }

    @Test
    fun `rule below the repeat threshold still runs`() =
        runTest {
            assertEquals(1, providerCalls(skipRepeat = true, dismissCount = 1))
        }

    @Test
    fun `object form without skipRepeatFindingTypes keeps the AI module running`() =
        runTest {
            assertEquals(1, providerCalls(skipRepeat = false, dismissCount = 5))
        }

    @Test
    fun `list form of feedback is still accepted`() =
        runTest {
            assertEquals(1, providerCalls(feedback = listOf(entry(dismissCount = 5)), feedbackEnabled = true))
        }

    private suspend fun providerCalls(
        skipRepeat: Boolean,
        dismissCount: Int,
    ): Int =
        providerCalls(
            feedback = mapOf(
                "enabled" to true,
                "skipRepeatFindingTypes" to skipRepeat,
                "dismissed" to listOf(entry(dismissCount)),
            ),
            feedbackEnabled = null,
        )

    private suspend fun providerCalls(
        feedback: Any,
        feedbackEnabled: Boolean?,
    ): Int {
        val requests = MutableStateFlow(0)
        val provider = OpenAiProvider(
            apiKey = "k",
            maxRetries = 0,
            httpClientEngine = MockEngine {
                requests.update { it + 1 }
                respond(OK_BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val base = buildTestRegistry()
        val module = AiRuleModule(
            object : ModuleRegistry by base {
                override fun aiProviders(): List<AiProviderModule> = listOf(provider)

                override fun findAiProvider(providerId: String): AiProviderModule? = provider.takeIf { it.providerId == providerId }
            },
        )
        val config = mapOf(
            "provider" to "openai",
            "apiKey" to "k",
            "model" to "gpt-4o-mini",
            "customRules" to listOf(mapOf("id" to "dry", "name" to "Dry", "prompt" to "Review.")),
            "feedback" to feedback,
        ) + listOfNotNull(feedbackEnabled?.let { "feedbackEnabled" to it })
        assertTrue(module.initialize(context(mapToJsonElement(config))), "feedback config must not disable the AI module")
        val mr = createTestMergeRequest(changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED, additions = 1, deletions = 0)))
        module.validate(RULE, mr, JsonObject(emptyMap()))
        module.shutdown()
        return requests.value
    }

    private fun entry(dismissCount: Int) =
        mapOf(
            "fingerprint" to "fp-1",
            "rule" to RULE,
            "filePath" to "src/A.kt",
            "messageHash" to 1,
            "dismissCount" to dismissCount,
            "firstDismissedAt" to 0,
            "lastDismissedAt" to 0,
        )

    private fun context(config: JsonElement) =
        object : PluginContext {
            override val pluginConfig: JsonElement = config

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private companion object {
        const val RULE = "ai-dry"
        const val OK_BODY =
            """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,""" +
                """"message":{"role":"assistant","content":"[]"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
    }
}
