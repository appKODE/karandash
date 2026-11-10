package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ai.consensusModels` + `ai.consensusStrategy`: the rule runs once per model and keeps the findings enough agree on. */
class AiRuleDispatcherConsensusTest {
    private val twoOfThree = mapOf("a" to listOf("X", "Y"), "b" to listOf("X", "Y"), "c" to listOf("X"))

    @Test
    fun `consensusStrategy intersection keeps only the findings every consensus model reports`() =
        runTest {
            val analyzer = PerModelAnalyzer(twoOfThree)
            val findings = dispatch(analyzer, consensus(listOf("a", "b", "c"), "intersection"))

            assertEquals(listOf("a", "b", "c"), analyzer.calls)
            assertEquals(listOf("X"), findings.map { it.message })
            assertEquals("a,b,c", findings.single().metadata[AiFindingMetadataKeys.AI_MODEL])
        }

    @Test
    fun `consensusStrategy majority keeps a finding two of three models report`() =
        runTest {
            val findings = dispatch(PerModelAnalyzer(twoOfThree), consensus(listOf("a", "b", "c"), "majority"))

            assertEquals(listOf("X", "Y"), findings.map { it.message }.sorted())
            assertEquals("a,b", findings.single { it.message == "Y" }.metadata[AiFindingMetadataKeys.AI_MODEL])
        }

    @Test
    fun `consensusStrategy first-agreement keeps a finding any two models report`() =
        runTest {
            val answers = mapOf("a" to listOf("Y"), "b" to emptyList(), "c" to listOf("Y"), "d" to emptyList())
            val majority = dispatch(PerModelAnalyzer(answers), consensus(answers.keys.toList(), "majority"))
            val firstAgreement = dispatch(PerModelAnalyzer(answers), consensus(answers.keys.toList(), "first_agreement"))

            assertEquals(emptyList(), majority.map { it.message }, "2 of 4 is no majority")
            assertEquals(listOf("Y"), firstAgreement.map { it.message })
        }

    @Test
    fun `consensusModels without analyzerConsensus asks the rule model once`() =
        runTest {
            val analyzer = PerModelAnalyzer(twoOfThree + ("m" to listOf("Y")))
            val findings = dispatch(analyzer, consensus(listOf("a", "b"), "intersection").copy(analyzerConsensus = false))

            assertEquals(listOf("m"), analyzer.calls)
            assertEquals(listOf("Y"), findings.map { it.message })
        }

    @Test
    fun `an mr-level analyzer asks the rule model once under consensus`() =
        runTest {
            val analyzer = PerModelAnalyzer(mapOf("m" to listOf("summary")), mrLevel = true)
            val findings = dispatch(analyzer, consensus(listOf("a", "b"), "intersection"))

            assertEquals(listOf("m"), analyzer.calls)
            assertEquals(listOf("summary"), findings.map { it.message })
        }

    @Test
    fun `a consensus model whose call fails does not vote and the rest still agree`() =
        runTest {
            val swallowed = PerModelAnalyzer(twoOfThree, failing = setOf("c"))
            val thrown = PerModelAnalyzer(twoOfThree, failing = setOf("c"), swallow = false)

            for (analyzer in listOf(swallowed, thrown)) {
                val findings = dispatch(analyzer, consensus(listOf("a", "b", "c"), "intersection"))
                assertEquals(listOf("X", "Y"), findings.map { it.message }.sorted())
                assertEquals("a,b", findings.first().metadata[AiFindingMetadataKeys.AI_MODEL])
            }
        }

    private fun consensus(
        models: List<String>,
        strategy: String,
    ) = AiPluginConfig(consensusModels = models, consensusStrategy = strategy, analyzerConsensus = true)

    private suspend fun dispatch(
        analyzer: PerModelAnalyzer,
        pluginCfg: AiPluginConfig,
    ): List<Finding> {
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == it.analyzerId }
        }
        val change = FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)
        return AiRuleDispatcher(registry, NoOpLogger)
            .dispatch(
                AiRuleDispatcher.DispatchInput(
                    ruleId = analyzer.analyzerId,
                    mr = createTestMergeRequest(changes = listOf(change)),
                    concurrencySemaphore = Semaphore(1),
                    aiProvider = UnusedProvider,
                    budget = TokenBudget(maxTokens = 100_000),
                    model = "m",
                    config = JsonObject(emptyMap()),
                    resolvedPrompt = null,
                    aiService = UnusedService,
                    aiRuleConfig = AiRuleConfig(),
                    pluginCfg = pluginCfg,
                    contextProcessors = emptyList(),
                    accumulatedFindings = emptyList(),
                    customRules = emptyList(),
                ),
            ).findings
    }

    /** Answers each model with the findings named in [answers]; records the models asked. */
    private class PerModelAnalyzer(
        private val answers: Map<String, List<String>>,
        override val mrLevel: Boolean = false,
        private val failing: Set<String> = emptySet(),
        private val swallow: Boolean = true,
    ) : AiAnalyzerModule {
        val calls = mutableListOf<String>()
        override val analyzerId: String = RULE
        override val id: String = RULE
        override val name: String = RULE
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val defaultEnabled: Boolean = true

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            calls += context.model
            if (context.model in failing) {
                // like a real analyzer: a provider error becomes "no findings" unless it propagates
                try {
                    context.aiService.complete(AiCompletionRequest(prompt = "p", model = context.model))
                } catch (e: AiProviderException) {
                    if (!swallow) throw e
                }
                return emptyList()
            }
            return answers[context.model].orEmpty().map {
                Finding(
                    rule = RULE,
                    severity = Severity.WARNING,
                    message = it,
                    location = Location.FileRef("A.kt", 1),
                    metadata = mapOf(AiFindingMetadataKeys.AI_MODEL to context.model),
                )
            }
        }
    }

    private object UnusedService : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = throw AiProviderException("rate limited", statusCode = 429)
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
        ): AiResponse = error("the analyzer never calls the provider")

        override suspend fun close() = Unit
    }

    private companion object {
        const val RULE = "consensus-probe"
        const val DIFF = "@@ -1 +1 @@\n-a\n+b\n"
    }
}
