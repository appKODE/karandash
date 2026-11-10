package io.karandash.plugins.ai

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.createTestWarning
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/** a `per-finding` metadata hook stores each finding's own reply, also for two findings of one rule. */
class AiPerFindingHookMetadataTest {
    private val service = object : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            val reply = "reply-for-" + request.prompt.removePrefix("About ")
            return AiCompletionResponse(content = reply, promptTokens = 1, completionTokens = 1, totalTokens = 2, model = "m")
        }
    }

    private val rawConfig: JsonElement = mapToJsonElement(
        mapOf("ai" to mapOf("hooks" to mapOf("per-finding" to mapOf("prompt" to "About {{finding_message}}", "action" to "metadata")))),
    )

    @Test
    fun `two findings of one rule keep their own per-finding metadata reply`() =
        runTest {
            val extension = configuredExtension()
            val first = createTestWarning(ruleId = RULE, message = "first")
            val second = createTestWarning(ruleId = RULE, message = "second")

            val result = extension.afterValidation(listOf(first, second), createTestMergeRequest(), rawConfig)

            val replies = result.map { it.metadata["aiHook.per-finding"] }
            assertEquals(listOf("reply-for-first", "reply-for-second"), replies, result.toString())
        }

    private suspend fun configuredExtension(): AiPipelineExtension {
        val serving = object : ModuleRegistry by buildTestRegistry() {
            override fun get(id: String): Module? = null

            override fun ruleModules(): List<RuleModule> = emptyList()
        }
        val statsDir = TestFiles.createTestTempDirectory("karandash-test-per-finding")
        val extension = AiPipelineExtension(serving)
        extension.initialize(context())
        extension.onConfigured(PipelineEnvironment(aiService = service, rawConfig = rawConfig, statsDir = statsDir.toString()))
        return extension
    }

    private fun context() = TestPluginContext()

    private companion object {
        const val RULE = "quiet"
    }
}
