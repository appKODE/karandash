package io.karandash.plugins.ai.dispatch

import io.karandash.ai.pipeline.AiServiceImpl
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.logger.NoOpLogger
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StructuredOutputGateTest {
    @Test
    fun `structuredOutput on sends the custom rule result schema to the provider`() =
        runTest {
            val schema = dispatchAndCaptureSchema(AiPluginConfig(structuredOutput = true))

            val properties = assertNotNull(schema).jsonObject.getValue("properties").jsonObject
            assertEquals(setOf("message", "explanation", "suggestion", "severity"), properties.keys)
        }

    @Test
    fun `structuredOutput off strips the schema before the provider`() =
        runTest {
            assertNull(dispatchAndCaptureSchema(AiPluginConfig(structuredOutput = false)))
        }

    @Test
    fun `structuredOutput is on by default`() =
        runTest {
            assertEquals(true, AiPluginConfig.fromPluginConfig(JsonObject(emptyMap())).structuredOutput)
            assertNotNull(dispatchAndCaptureSchema(AiPluginConfig()))
        }

    @Test
    fun `structuredOutput parses from the ai block`() {
        val config = AiPluginConfig.fromPluginConfig(
            AiPluginConfig.topLevelAiBlockToPluginConfigElement(JsonObject(mapOf("structuredOutput" to JsonPrimitive(true)))),
        )

        assertEquals(true, config.structuredOutput)
    }

    private suspend fun dispatchAndCaptureSchema(pluginCfg: AiPluginConfig): JsonElement? {
        val schemas = mutableListOf<JsonElement?>()
        val provider = object : AiProviderModule {
            override val providerId = FAKE
            override val id = "io.karandash.ai.provider.$FAKE"
            override val name = FAKE
            override val version = "test"
            override val description = FAKE
            override val defaultModel = "m"
            override val apiKeyEnvVar: String? = null
            override val requiresApiKey = false

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
                schemas += request.responseSchema
                return AiCompletionResponse("""{"message": "found"}""", 1, 1, 2, request.model)
            }
        }
        val aiService = AiServiceImpl(buildTestRegistry(), FAKE, localProviders = mapOf(FAKE to provider)).forRule(RULE_ID, null)
        AiRuleDispatcher(buildTestRegistry(), NoOpLogger).dispatch(
            AiRuleDispatcher.DispatchInput(
                ruleId = RULE_ID,
                mr = createTestMergeRequest(),
                concurrencySemaphore = Semaphore(1),
                aiProvider = UnusedProvider,
                budget = TokenBudget(maxTokens = 100_000),
                model = "m",
                config = JsonObject(emptyMap()),
                resolvedPrompt = null,
                aiService = aiService,
                aiRuleConfig = AiRuleConfig(),
                pluginCfg = pluginCfg,
                contextProcessors = emptyList(),
                accumulatedFindings = emptyList(),
                customRules = listOf(CustomAiRule(id = RULE_ID, name = "Mine", prompt = "Check it")),
            ),
        )
        return schemas.single()
    }

    private object UnusedProvider : AiProvider {
        override val providerId = "unused"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = error("the service path must be used")

        override suspend fun close() = Unit
    }

    private companion object {
        const val FAKE = "fake"
        const val RULE_ID = "my-custom"
    }
}
