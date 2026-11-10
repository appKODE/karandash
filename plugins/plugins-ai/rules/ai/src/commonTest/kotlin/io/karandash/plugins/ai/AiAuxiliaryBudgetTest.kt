package io.karandash.plugins.ai

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiService
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.config.keys.ModuleIds
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.ResponseCacheSalt
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.createTestWarning
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * auxiliary AI calls (hooks, constitutional review, enricher, digest) are charged to the analyzers that
 * triggered them, never to the shared run budget; `cost-budget.max-tokens-per-run` stays as the safety net.
 */
class AiAuxiliaryBudgetTest {
    private val warnings = mutableListOf<String>()
    private val infos = mutableListOf<String>()

    private val recording = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) {
            infos += message
        }

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val service = object : AiService {
        var calls = 0
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            calls++
            return AiCompletionResponse(content = "ok", promptTokens = 600, completionTokens = 100, totalTokens = HOOK_TOKENS, model = "m")
        }
    }

    private val rawConfig: JsonElement = mapToJsonElement(
        mapOf(
            "ai" to mapOf(
                "hooks" to mapOf(
                    "post-fetch" to mapOf("prompt" to "Look at {{mr_title}}"),
                    "post-validation" to mapOf("prompt" to "Summarize {{findings_json}}"),
                ),
            ),
        ),
    )

    @Test
    fun `a post-validation hook call is charged to the analyzer whose findings triggered it`() =
        runTest {
            val module = initializedRule(listOf(QuietAnalyzer))
            val budget = checkNotNull(module.runLimits()?.first)
            val extension = configuredExtension(module)

            extension.afterValidation(listOf(createTestWarning(ruleId = RULE)), createTestMergeRequest(), rawConfig)

            assertEquals(1, service.calls)
            assertEquals(BUDGET, budget.remaining(), "the auxiliary call shrank the shared run budget")
            val summary = module.consumeCostSummaryLines()
            assertTrue(summary.any { it.startsWith("  $RULE:") && "$HOOK_TOKENS auxiliary" in it }, summary.toString())
            module.shutdown()
        }

    @Test
    fun `an auxiliary call still runs once the run budget is spent`() =
        runTest {
            val module = initializedRule(listOf(QuietAnalyzer))
            checkNotNull(module.runLimits()?.first).recordSpend(BUDGET)
            val extension = configuredExtension(module)

            extension.afterValidation(listOf(createTestWarning(ruleId = RULE)), createTestMergeRequest(), rawConfig)

            assertEquals(1, service.calls, "the hook was skipped because the analyzers spent the run budget")
            assertTrue(warnings.none { "skipped: budget" in it }, warnings.toString())
            module.shutdown()
        }

    @Test
    fun `the max-tokens-per-run safety net skips an auxiliary stage with an info finding`() =
        runTest {
            val module = initializedRule(listOf(QuietAnalyzer), extra = mapOf("costBudget" to mapOf("maxTokensPerRun" to CAP)))
            checkNotNull(module.runLimits()?.first).recordSpend(CAP)
            val extension = configuredExtension(module)
            val mr = createTestMergeRequest()

            val validated = extension.afterValidation(listOf(createTestWarning(ruleId = RULE)), mr, rawConfig)
            val reported = extension.beforeReporting(validated, mr)

            assertEquals(0, service.calls, "the hook ran past cost-budget.max-tokens-per-run")
            val notice = reported.filter { it.severity == Severity.INFO && "skipped: budget" in it.message }
            assertEquals(1, notice.size, reported.toString())
            assertTrue("hooks" in notice.single().message, notice.single().message)
            assertTrue(infos.any { "skipped: budget" in it && "hooks" in it }, infos.toString())
            module.shutdown()
        }

    /**
     * Fixture shaped like `base@1.41.0`: `ai: { max-token-budget: 30000, code-suggestions: true }`
     * with no per-analyzer `max-tokens`, a large MR, and post-fetch plus post-validation hooks. Every analyzer
     * runs, the run budget holds only analyzer spend, and the hook tokens show up under the analyzers.
     */
    @Test
    fun `base 1_41_0 config keeps every analyzer and charges hooks to the triggering analyzers`() =
        runTest {
            val analyzers = listOf(
                SpendingAnalyzer("risk-scoring", "riskScoring", defaultEnabled = true),
                SpendingAnalyzer("review-comments", "reviewComments", defaultEnabled = true),
                SpendingAnalyzer("code-suggestions", "codeSuggestions", defaultEnabled = false),
            )
            val module = initializedRule(analyzers, extra = mapOf("maxTokenBudget" to BASE_BUDGET, "codeSuggestions" to true))
            val budget = checkNotNull(module.runLimits()?.first)
            val extension = configuredExtension(module)
            val mr = largeMr()

            extension.onPostFetch(mr, rawConfig)
            // the engine runs every contributed rule; code-suggestions runs as its own analyzer on its own budget
            val rules = module.contributedRules()
            val findings = rules.flatMap { module.validate(it, mr, JsonObject(emptyMap())) }
            extension.afterValidation(findings, mr, rawConfig)

            assertEquals(setOf("risk-scoring", "review-comments", "code-suggestions"), rules)
            assertEquals(rules, analyzers.filter { it.spent > 0 }.map { it.analyzerId }.toSet(), "an analyzer did not run")
            // code-suggestions gets its own share; without cost-budget the run grows to fund every rule
            // review-comments sample 2 spends a budget of its own, never the run budget
            assertEquals(
                analyzers.sumOf { it.spent - it.sampleSpent },
                assertIs<TokenBudget>(budget).totalConsumed(),
                "auxiliary spend reached the shared run budget"
            )
            assertEquals(2, service.calls, "a hook was skipped: $warnings")
            val summary = module.consumeCostSummaryLines()
            rules.forEach { rule ->
                assertTrue(summary.any { it.startsWith("  $rule:") && "auxiliary" in it }, summary.toString())
            }
            module.shutdown()
        }

    private fun largeMr(): MergeRequest =
        createTestMergeRequest(
            changes = (1..LARGE_MR_FILES).map { i ->
                FileChange(
                    "src/File$i.kt",
                    ChangeType.MODIFIED,
                    additions = 1,
                    deletions = 0,
                    diffContent = "@@ -1 +1 @@\n-old$i\n+new$i\n"
                )
            },
        )

    private suspend fun configuredExtension(module: AiRuleModule): AiPipelineExtension {
        val serving = object : ModuleRegistry by buildTestRegistry() {
            override fun get(id: String): Module? = if (id == ModuleIds.AI_ANALYSIS) module else null

            override fun ruleModules(): List<RuleModule> = listOf(module)
        }
        val statsDir = TestFiles.createTestTempDirectory("karandash-test-aux-budget")
        val extension = AiPipelineExtension(serving)
        extension.initialize(context(emptyMap()))
        extension.onConfigured(PipelineEnvironment(aiService = service, rawConfig = rawConfig, statsDir = statsDir.toString()))
        return extension
    }

    private suspend fun initializedRule(
        analyzers: List<AiAnalyzerModule>,
        extra: Map<String, Any> = emptyMap(),
    ): AiRuleModule {
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun aiAnalyzers(): List<AiAnalyzerModule> = analyzers

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzers.find { it.analyzerId == analyzerId }

            override fun aiProviders(): List<AiProviderModule> = listOf(FakeProvider)

            override fun findAiProvider(providerId: String): AiProviderModule? = FakeProvider.takeIf { providerId == FAKE }
        }
        val module = AiRuleModule(registry)
        val config = mapOf("provider" to FAKE, "model" to "m", "maxTokenBudget" to BUDGET) + extra
        assertTrue(module.initialize(context(config)))
        return module
    }

    private fun context(config: Map<String, Any>) =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)
            override val logger: Logger = recording

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {
                warnings += message
            }

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    /** An analyzer that makes one provider call per invocation and records its tokens, like the built-in ones. */
    private class SpendingAnalyzer(
        override val analyzerId: String,
        override val featureKey: String,
        override val defaultEnabled: Boolean,
    ) : AiAnalyzerModule {
        var spent = 0
        var sampleSpent = 0
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val estimatedTokens: Int = SPENDING_ESTIMATE

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            val response = context.aiService.complete(AiCompletionRequest(prompt = "review", model = context.model), null)
            context.budget.recordSpend(response.totalTokens)
            spent += response.totalTokens
            if (ResponseCacheSalt.current() != null) sampleSpent += response.totalTokens
            return listOf(createTestWarning(ruleId = analyzerId, message = "$analyzerId finding"))
        }
    }

    private object FakeProvider : AiProviderModule {
        override val providerId: String = FAKE
        override val id: String = "io.karandash.ai.provider.$FAKE"
        override val name: String = FAKE
        override val version: String = "test"
        override val description: String = FAKE
        override val defaultModel: String = "m"
        override val apiKeyEnvVar: String? = null
        override val requiresApiKey: Boolean = false

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse =
            AiCompletionResponse("""{"findings": []}""", ANALYZER_TOKENS - 1000, 1000, ANALYZER_TOKENS, request.model)
    }

    private object QuietAnalyzer : AiAnalyzerModule {
        override val analyzerId: String = RULE
        override val id: String = RULE
        override val name: String = RULE
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = RULE
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 100

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = emptyList()
    }

    private companion object {
        const val RULE = "quiet"
        const val FAKE = "fake"
        const val BUDGET = 5000
        const val CAP = 1000
        const val HOOK_TOKENS = 700
        const val BASE_BUDGET = 30_000
        const val SPENDING_ESTIMATE = 3000
        const val ANALYZER_TOKENS = 10_000
        const val LARGE_MR_FILES = 60
    }
}
