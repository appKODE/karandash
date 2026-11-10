package io.karandash.plugins.ai

import io.karandash.ai.pipeline.AiServiceImpl
import io.karandash.api.ai.AiContextProcessor
import io.karandash.api.context.EnrichmentContext
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.PromptTokenCalibration
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.dispatch.AiFeedbackCostFilter
import io.karandash.plugins.ai.dispatch.AiMrPreprocessor
import io.karandash.plugins.ai.dispatch.AiPromptAssembler
import io.karandash.plugins.ai.dispatch.AiRuleDispatcher
import io.karandash.plugins.ai.dispatch.AiSkipEvaluator
import io.karandash.plugins.ai.dispatch.BudgetPlanDump
import io.karandash.plugins.ai.prompts.PromptResolver
import io.karandash.plugins.ai.prompts.PromptSource
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.CachingAiProvider
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.state.snapshot.SnapshotManager
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.TimeSource

/** Not @Serializable: runtime context holder, holds a non-serializable `EnrichmentContext`. */
internal data class AiRuleInitState(
    val runtimeContext: AiRuntimeContext,
    val provider: AiProvider?,
    val tokenBudget: TokenBudget?,
    val enabledRules: Set<String>,
    val model: String,
    val failOnError: Boolean,
    val pluginCfg: AiPluginConfig,
    val aiRuleConfig: AiRuleConfig,
    val concurrencySemaphore: Semaphore,
    val perRuleModels: Map<String, String>,
    val perRuleTemperatures: Map<String, Double> = emptyMap(),
    val normalizedRuleProviders: Map<String, String>,
    val strictness: ReviewStrictness,
    val customRules: List<CustomAiRule>,
    val namedProviders: Map<String, AiProvider>,
    val perRuleBudgets: Map<String, TokenBudget>,
    val aiService: AiServiceImpl?,
    val promptSources: Map<String, PromptSource>,
    val promptResolver: PromptResolver,
    val statePersistence: AiStatePersistence?,
    val cachingProvider: CachingAiProvider?,
    val currentMrKey: String?,
    val pluginStartMark: TimeSource.Monotonic.ValueTimeMark?,
    val snapshotManager: SnapshotManager?,
    val feedbackStore: FeedbackStore,
    val skipEvaluator: AiSkipEvaluator,
    val mrPreprocessor: AiMrPreprocessor,
    val dispatcher: AiRuleDispatcher,
    val postProcessor: AiFeedbackCostFilter,
    val promptAssembler: AiPromptAssembler,
    val contextProcessors: List<AiContextProcessor> = emptyList(),
    val enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
    val ruleEnrichmentBindingPrefixes: Map<String, List<String>> = emptyMap(),
    /** Actual/estimated prompt tokens per model so far; one per `initialize`, shared by the run's rules. */
    val calibration: PromptTokenCalibration = PromptTokenCalibration(),
    /** The MR's `ai/budget-plan.json`, its refusals added as the rules run; null before the MR is planned. */
    val budgetPlan: BudgetPlanDump? = null,
)
