package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiService
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.pipeline.ReviewSamplingConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The MR context is built once and sent as the same leading blocks by every analyzer; analyzer text goes last. */
class SharedContextPrefixTest {
    private val service = RecordingService()
    private val analyzers = listOf(PromptingAnalyzer("review-comments"), PromptingAnalyzer("risk-scoring"))
    private val registry = object : ModuleRegistry by buildTestRegistry() {
        override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzers.firstOrNull { it.analyzerId == analyzerId }
    }

    @Test
    fun `two analyzers send byte-identical shared context ahead of their own prompt`() =
        runTest {
            val requests = fullRequests(sharedSystemPrompt = false)

            val prefix = SHARED.joinToString("\n\n") { it.content }
            assertEquals(listOf("system review-comments", "system risk-scoring"), requests.map { it.substringBefore("\n\n") })
            requests.forEach { assertTrue(it.substringAfter("\n\n").startsWith(prefix), it) }
            assertEquals(listOf("own review-comments", "own risk-scoring"), requests.map { it.substringAfter(prefix).trim() })
            assertEquals(
                1,
                service.requests
                    .map { it.contextBlocks }
                    .distinct()
                    .size
            )
        }

    @Test
    fun `sharedSystemPrompt makes the system prompt and shared blocks one prefix and moves instructions after them`() =
        runTest {
            val requests = fullRequests(sharedSystemPrompt = true)

            val prefix = listOf(SharedContextService.SHARED_PREAMBLE).plus(SHARED.map { it.content }).joinToString("\n\n")
            requests.forEach { assertTrue(it.startsWith(prefix), it) }
            assertEquals(
                listOf("review-comments", "risk-scoring").map { "${SharedContextService.INSTRUCTIONS_HEADING}\nsystem $it\n\nown $it" },
                requests.map { it.removePrefix(prefix).trim() },
            )
        }

    /** Each recorded request as the provider sees it, system prompt first: the order a prompt cache keys on. */
    private suspend fun fullRequests(sharedSystemPrompt: Boolean): List<String> {
        val dispatcher = AiRuleDispatcher(registry, NoOpLogger)
        val mr = createTestMergeRequest(changes = listOf(change("A.kt")))
        analyzers.forEach { dispatcher.dispatch(input(it.analyzerId, mr, sharedSystemPrompt)) }
        return service.requests.map { request ->
            listOfNotNull(request.systemPrompt, request.withContextInPrompt().prompt).joinToString("\n\n")
        }
    }

    @Test
    fun `an analyzer sees the shared blocks it is sent`() =
        runTest {
            AiRuleDispatcher(
                registry,
                NoOpLogger
            ).dispatch(input("review-comments", createTestMergeRequest(changes = listOf(change("A.kt")))))

            assertEquals(
                SHARED,
                analyzers
                    .first()
                    .contexts
                    .single()
                    .contextBlocks
            )
        }

    @Test
    fun `the diff context is built once for analyzers with the same inputs`() =
        runTest {
            val dispatcher = AiRuleDispatcher(registry, NoOpLogger)
            val mr = createTestMergeRequest(changes = listOf(change("A.kt")))

            analyzers.forEach { dispatcher.dispatch(input(it.analyzerId, mr)) }

            assertSame(diffOf(analyzers[0]), diffOf(analyzers[1]))
        }

    @Test
    fun `a different MR gets its own diff context`() =
        runTest {
            val dispatcher = AiRuleDispatcher(registry, NoOpLogger)

            dispatcher.dispatch(input("review-comments", createTestMergeRequest(changes = listOf(change("A.kt")))))
            dispatcher.dispatch(input("risk-scoring", createTestMergeRequest(changes = listOf(change("B.kt")))))

            assertNotSame(diffOf(analyzers[0]), diffOf(analyzers[1]))
            assertTrue("B.kt" in diffOf(analyzers[1]).preparedDiff)
        }

    private fun diffOf(analyzer: PromptingAnalyzer): PrecomputedDiffContext = analyzer.contexts.single().diffContext

    private fun input(
        ruleId: String,
        mr: MergeRequest,
        sharedSystemPrompt: Boolean = false,
    ) = AiRuleDispatcher.DispatchInput(
        ruleId = ruleId,
        mr = mr,
        concurrencySemaphore = Semaphore(1),
        aiProvider = UnusedProvider,
        budget = TokenBudget(maxTokens = 100_000),
        model = "m",
        config = JsonObject(emptyMap()),
        resolvedPrompt = null,
        aiService = service,
        aiRuleConfig = AiRuleConfig(),
        pluginCfg = AiPluginConfig(structuredOutput = false, sharedSystemPrompt = sharedSystemPrompt, reviewSampling = ONE_SAMPLE),
        contextProcessors = emptyList(),
        accumulatedFindings = emptyList(),
        customRules = emptyList(),
        contextBlocks = SHARED,
    )

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = "@@ -1 +1 @@\n-a\n+b")

    private class PromptingAnalyzer(
        override val analyzerId: String,
    ) : AiAnalyzerModule {
        val contexts = mutableListOf<AiAnalyzerContext>()
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 0

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            contexts += context
            context.aiService.complete(AiCompletionRequest(prompt = "own $analyzerId", systemPrompt = "system $analyzerId", model = "m"))
            return emptyList()
        }
    }

    private class RecordingService : AiService {
        val requests = mutableListOf<AiCompletionRequest>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request
            return AiCompletionResponse("[]", 1, 1, 2, request.model)
        }
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
        val ONE_SAMPLE = ReviewSamplingConfig(samples = 1)
        val SHARED = listOf(
            AiContextBlock(heading = "", content = "## Ticket\nABC-1 body", priority = 0, source = "ticket-content-enricher"),
            AiContextBlock(heading = "", content = "## CI\nbuild failed", priority = 0, source = "ci-context-enricher"),
        )
    }
}
