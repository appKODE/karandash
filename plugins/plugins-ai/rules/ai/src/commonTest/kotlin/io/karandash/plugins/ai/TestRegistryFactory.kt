package io.karandash.plugins.ai

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.models.finding.Finding

/**
 * Returns a [ModuleRegistry] stub that exposes the same 15 AI analyzer descriptors that were
 * previously hard-coded in AiRuleModule. Used by unit tests that exercise [AiRuleModule]
 * without a real application context.
 */
@Suppress("TooManyFunctions")
internal fun buildTestRegistry(): ModuleRegistry =
    object : ModuleRegistry {
        private val analyzers: List<AiAnalyzerModule> = buildAnalyzerList()

        override fun aiAnalyzers(): List<AiAnalyzerModule> = analyzers

        override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzers.find { it.analyzerId == analyzerId }

        override suspend fun register(module: Module) = Unit

        override suspend fun unregister(id: String): Boolean = false

        override fun get(id: String): Module? = null

        override fun getByType(type: ModuleType): List<Module> = emptyList()

        override fun ruleModules(): List<RuleModule> = emptyList()

        override fun platformServices(): List<PlatformServiceModule> = emptyList()

        override fun reporters(): List<ReporterModule> = emptyList()

        override fun registeredIds(): Set<String> = emptySet()

        override fun hasRule(ruleId: String): Boolean = false

        override fun findRuleHandler(ruleId: String): RuleModule? = null

        override fun findByAlias(alias: String): PlatformServiceModule? = null

        override fun allAliases(): Map<String, PlatformServiceModule> = emptyMap()

        override fun aliasCollisions(): List<String> = emptyList()

        override fun cliCommandModules(): List<CliCommandModule> = emptyList()

        override fun aiProviders(): List<AiProviderModule> = testAiProviders

        override fun findAiProvider(providerId: String): AiProviderModule? = testAiProviders.find { it.providerId == providerId }

        override fun aiInterceptors(): List<AiInterceptorModule> = emptyList()
    }

private val testAiProviders: List<AiProviderModule> = listOf(
    stubAiProvider("openai", defaultModel = "gpt-4o-mini", apiKeyEnvVar = "OPENAI_API_KEY", requiresApiKey = true),
    stubAiProvider("anthropic", defaultModel = "claude-sonnet-4-20250514", apiKeyEnvVar = "ANTHROPIC_API_KEY", requiresApiKey = true),
    stubAiProvider("gemini", defaultModel = "gemini-2.0-flash", apiKeyEnvVar = "GEMINI_API_KEY", requiresApiKey = true),
    stubAiProvider("ollama", defaultModel = "llama3.2", apiKeyEnvVar = null, requiresApiKey = false),
    stubAiProvider("litellm", defaultModel = "gpt-4o-mini", apiKeyEnvVar = "LITELLM_API_KEY", requiresApiKey = false),
)

private fun stubAiProvider(
    providerIdValue: String,
    defaultModel: String,
    apiKeyEnvVar: String?,
    requiresApiKey: Boolean,
): AiProviderModule =
    object : AiProviderModule {
        override val providerId: String = providerIdValue
        override val id: String = "io.karandash.ai.provider.$providerIdValue"
        override val name: String = providerIdValue
        override val version: String = "test"
        override val description: String = providerIdValue
        override val defaultModel: String = defaultModel
        override val apiKeyEnvVar: String? = apiKeyEnvVar
        override val requiresApiKey: Boolean = requiresApiKey

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse =
            error("stub provider does not support complete()")
    }

private fun buildAnalyzerList(): List<AiAnalyzerModule> =
    listOf(
        stubAnalyzer("risk-scoring", "riskScoring", enabled = true, tokens = 1500, scope = true),
        stubAnalyzer("description-quality", "descriptionQuality", enabled = false, tokens = 1500, scope = true),
        stubAnalyzer("change-categorization", "changeCategorization", enabled = false, tokens = 1500, scope = true),
        stubAnalyzer("review-comments", "reviewComments", enabled = true, tokens = 3000, scope = true),
        stubAnalyzer("similar-issues", "similarIssues", enabled = false, tokens = 2000, scope = true),
        stubAnalyzer("fix-suggestions", "fixSuggestions", enabled = false, tokens = 2500, scope = true),
        stubAnalyzer("code-suggestions", "codeSuggestions", enabled = false, tokens = 3000, scope = true),
        stubAnalyzer("ticket-compliance", "ticketCompliance", enabled = false, tokens = 2500, scope = false),
        stubAnalyzer("pr-split", "prSplit", enabled = false, tokens = 2000, scope = false),
        stubAnalyzer("generate-description", "generateDescription", enabled = false, tokens = 2500, scope = false),
        stubAnalyzer("outdated-docs", "outdatedDocs", enabled = false, tokens = 1500, scope = false),
        stubAnalyzer("review-thread-analysis", "reviewThreadAnalysis", enabled = false, tokens = 1200, scope = false),
        stubAnalyzer("mr-summary", "mrSummary", enabled = false, tokens = 2000, scope = false),
        stubAnalyzer("code-quality", "codeQuality", enabled = false, tokens = 3500, scope = true),
        stubAnalyzer("ai-ownership-analysis", "ownershipAnalysis", enabled = false, tokens = 3000, scope = false),
    )

@Suppress("LongParameterList")
private fun stubAnalyzer(
    id: String,
    featureKeyValue: String,
    enabled: Boolean,
    tokens: Int,
    scope: Boolean,
): AiAnalyzerModule =
    object : AiAnalyzerModule {
        override val id: String = id
        override val name: String = id
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val analyzerId: String = id
        override val featureKey: String = featureKeyValue
        override val defaultEnabled: Boolean = enabled
        override val estimatedTokens: Int = tokens
        override val needsScopeDetection: Boolean = scope
        override val needsBlame: Boolean = featureKeyValue == "ownershipAnalysis"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = emptyList()
    }
