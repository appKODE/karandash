package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiService
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.context.DesignDocEnricher
import io.karandash.plugins.ai.core.context.TicketContentEnricher
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An analyzer's own skip reason says when the context it needed was in the plan but dropped for `ai.budget`. */
class AiRuleDispatcherSkipReasonTest {
    @Test
    fun `a skip with no context dropped keeps the analyzer reason`() =
        runTest {
            assertEquals(REASON, skipReason(dropped = emptyList()))
        }

    @Test
    fun `a skip after the budget dropped context names what was dropped`() =
        runTest {
            val reason = checkNotNull(skipReason(dropped = listOf(DesignDocEnricher.MODULE_ID)))

            assertTrue(reason.startsWith(REASON), reason)
            assertTrue(DesignDocEnricher.MODULE_ID in reason && "ai.budget" in reason, reason)
        }

    @Test
    fun `a cut block the analyzer reads is named too`() =
        runTest {
            val reason = checkNotNull(skipReason(dropped = listOf("${DesignDocEnricher.MODULE_ID} (cut to 10 of 99 chars)")))

            assertTrue("ai.budget" in reason, reason)
        }

    @Test
    fun `a dropped block the analyzer does not read adds nothing to its reason`() =
        runTest {
            assertEquals(REASON, skipReason(dropped = listOf(TicketContentEnricher.MODULE_ID, "crossFileContext")))
        }

    private suspend fun skipReason(dropped: List<String>): String? {
        val analyzer = SkippingAnalyzer(RULE)
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == RULE }

            override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(DesignDocEnricher(), TicketContentEnricher())
        }
        val a = FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = DIFF)
        val result = AiRuleDispatcher(registry, NoOpLogger).dispatch(
            AiRuleDispatcher.DispatchInput(
                ruleId = RULE,
                mr = createTestMergeRequest(changes = listOf(a)),
                concurrencySemaphore = Semaphore(1),
                aiProvider = UnusedProvider,
                budget = TokenBudget(maxTokens = 100_000),
                model = "m",
                config = JsonObject(emptyMap()),
                resolvedPrompt = null,
                aiService = UnusedService,
                aiRuleConfig = AiRuleConfig(),
                pluginCfg = AiPluginConfig(),
                contextProcessors = emptyList(),
                accumulatedFindings = emptyList(),
                customRules = emptyList(),
                contextDropped = dropped,
            ),
        )
        return result.coverage?.skipReason
    }

    private class SkippingAnalyzer(
        override val analyzerId: String,
    ) : AiAnalyzerModule {
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 0
        override val supportedEnrichment: List<String> = listOf("confluence")

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = context.skip(REASON)
    }

    private object UnusedService : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = error("the fixed analyzer never calls the service")
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
        ): AiResponse = error("analyzers call through AiService")

        override suspend fun close() = Unit
    }

    private companion object {
        const val RULE = "design-doc-drift"
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
        const val REASON = "no design doc content in enrichment context"
    }
}
