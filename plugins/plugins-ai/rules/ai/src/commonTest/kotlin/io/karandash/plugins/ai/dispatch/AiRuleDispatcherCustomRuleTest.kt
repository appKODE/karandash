package io.karandash.plugins.ai.dispatch

import io.karandash.ai.pipeline.AiServiceImpl
import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
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
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AiRuleDispatcherCustomRuleTest {
    @Test
    fun `a custom rule calls the AI service with its rule id instead of the raw provider`() =
        runTest {
            val seenRuleIds = mutableListOf<String?>()
            val interceptor = object : AiInterceptorModule {
                override val id = "recording-interceptor"
                override val name = id
                override val version = "test"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun intercept(
                    request: AiCompletionRequest,
                    context: AiInterceptContext,
                    chain: AiInterceptChain,
                ): AiCompletionResponse {
                    seenRuleIds += context.ruleId
                    return chain.proceed(request)
                }
            }
            val registry = object : ModuleRegistry by buildTestRegistry() {
                override fun aiInterceptors(): List<AiInterceptorModule> = listOf(interceptor)
            }
            val aiService = AiServiceImpl(registry, FAKE, localProviders = mapOf(FAKE to ServiceProvider()))
                .forRule(RULE_ID, null)
            val rawCalls = mutableListOf<String>()
            val rawProvider = object : AiProvider {
                override val providerId = FAKE

                override suspend fun complete(
                    prompt: String,
                    systemPrompt: String?,
                    model: String,
                    maxTokens: Int,
                    temperature: Double,
                    jsonMode: Boolean,
                ): AiResponse {
                    rawCalls += prompt
                    return AiResponse("""{"message": "raw"}""", 0, 0, 0, model)
                }

                override suspend fun close() = Unit
            }

            val result = AiRuleDispatcher(buildTestRegistry(), NoOpLogger).dispatch(
                AiRuleDispatcher.DispatchInput(
                    ruleId = RULE_ID,
                    mr = createTestMergeRequest(),
                    concurrencySemaphore = Semaphore(1),
                    aiProvider = rawProvider,
                    budget = TokenBudget(maxTokens = 100_000),
                    model = "m",
                    config = JsonObject(emptyMap()),
                    resolvedPrompt = null,
                    aiService = aiService,
                    aiRuleConfig = AiRuleConfig(),
                    pluginCfg = AiPluginConfig(),
                    contextProcessors = emptyList(),
                    accumulatedFindings = emptyList(),
                    customRules = listOf(CustomAiRule(id = RULE_ID, name = "Mine", prompt = "Check it")),
                ),
            )

            assertEquals(listOf<String?>(RULE_ID), seenRuleIds)
            assertEquals(emptyList(), rawCalls)
            assertEquals(listOf("via service"), result.findings.map { it.message })
        }

    private class ServiceProvider : AiProviderModule {
        override val providerId = FAKE
        override val id = "io.karandash.ai.provider.$FAKE"
        override val name = FAKE
        override val version = "test"
        override val description = FAKE
        override val defaultModel = "m"
        override val apiKeyEnvVar: String? = null
        override val requiresApiKey = false

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun complete(request: AiCompletionRequest) =
            AiCompletionResponse("""{"message": "via service"}""", 1, 1, 2, request.model)
    }

    private companion object {
        const val FAKE = "fake"
        const val RULE_ID = "my-custom"
    }
}
