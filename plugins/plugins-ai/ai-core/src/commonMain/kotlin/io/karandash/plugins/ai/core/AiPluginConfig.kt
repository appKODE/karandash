package io.karandash.plugins.ai.core

import io.karandash.api.PipelineExtensionConfig
import io.karandash.api.config.PreserveKeys
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.platform.FetchOptions
import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.architecture.AdrConstraint
import io.karandash.plugins.ai.core.config.AiAnalyzerConfig
import io.karandash.plugins.ai.core.config.AiAnalyzerTokensConfig
import io.karandash.plugins.ai.core.config.AiBudgetConfig
import io.karandash.plugins.ai.core.config.AiConfigAliases
import io.karandash.plugins.ai.core.config.AiDebugConfig
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.plugins.ai.core.config.AiOllamaConfig
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.plugins.ai.core.config.AiResearchConfig
import io.karandash.plugins.ai.core.config.AiStatisticsConfig
import io.karandash.plugins.ai.core.config.AiStubConfig
import io.karandash.plugins.ai.core.config.CalibrationConfig
import io.karandash.plugins.ai.core.config.CanaryConfig
import io.karandash.plugins.ai.core.config.ChunkingConfig
import io.karandash.plugins.ai.core.config.CompositionRule
import io.karandash.plugins.ai.core.config.ConditionalSection
import io.karandash.plugins.ai.core.config.CostBudgetConfig
import io.karandash.plugins.ai.core.config.CrossFileContextConfig
import io.karandash.plugins.ai.core.config.DefectPredictorConfig
import io.karandash.plugins.ai.core.config.EnforcementPhase
import io.karandash.plugins.ai.core.config.ExecutionProfileConfig
import io.karandash.plugins.ai.core.config.GracePeriodConfig
import io.karandash.plugins.ai.core.config.GracePeriodWindowConfig
import io.karandash.plugins.ai.core.config.IncrementalConfig
import io.karandash.plugins.ai.core.config.InlineOverridesConfig
import io.karandash.plugins.ai.core.config.MacroDefinition
import io.karandash.plugins.ai.core.config.MacroReference
import io.karandash.plugins.ai.core.config.NotificationRoute
import io.karandash.plugins.ai.core.config.PreMergeCheck
import io.karandash.plugins.ai.core.config.ProcessRiskConfig
import io.karandash.plugins.ai.core.config.RoutingConfig
import io.karandash.plugins.ai.core.config.SandboxConfig
import io.karandash.plugins.ai.core.config.SeniorityTier
import io.karandash.plugins.ai.core.config.SeverityOverride
import io.karandash.plugins.ai.core.config.TeamConfig
import io.karandash.plugins.ai.core.config.TestGapConfig
import io.karandash.plugins.ai.core.config.ThreadCommandsConfig
import io.karandash.plugins.ai.core.config.TrackConfig
import io.karandash.plugins.ai.core.config.ViolationBaselineConfig
import io.karandash.plugins.ai.core.config.ViolationBudgetConfig
import io.karandash.plugins.ai.core.pipeline.AgreementConfig
import io.karandash.plugins.ai.core.pipeline.ConstitutionConfig
import io.karandash.plugins.ai.core.pipeline.ContradictionsConfig
import io.karandash.plugins.ai.core.pipeline.CorrelationConfig
import io.karandash.plugins.ai.core.pipeline.DedupConfig
import io.karandash.plugins.ai.core.pipeline.EnricherConfig
import io.karandash.plugins.ai.core.pipeline.FilterConfig
import io.karandash.plugins.ai.core.pipeline.GroundingConfig
import io.karandash.plugins.ai.core.pipeline.HintsConfig
import io.karandash.plugins.ai.core.pipeline.RankingConfig
import io.karandash.plugins.ai.core.pipeline.ReviewSamplingConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.CodeSuggestionLimits
import io.karandash.sdk.Logger
import io.karandash.sdk.config.deserializeConfig
import io.karandash.sdk.content.ContentSource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed representation of the AI plugin's module-level configuration.
 *
 * Parsed once from the raw `pluginConfig` element during module initialization
 * so that all downstream helper methods can use typed property access instead of
 * repeated key lookups with unchecked casts.
 */
@Serializable
data class AiPluginConfig(
    val provider: String = "openai",
    val apiKey: String? = null,
    val model: String? = null,
    val failOnError: Boolean = false,
    val maxTokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    val strictness: String = "standard",
    val bestPractices: ContentSource? = null,
    val extraInstructions: ContentSource? = null,
    val modelFallback: List<String> = emptyList(),
    @PreserveKeys
    val features: Map<String, JsonElement> = emptyMap(),
    @PreserveKeys
    val promptOverrides: Map<String, String> = emptyMap(),
    @PreserveKeys
    val prompts: Map<String, JsonElement> = emptyMap(),
    @PreserveKeys
    val models: Map<String, String> = emptyMap(),
    @PreserveKeys
    val temperatures: Map<String, Double> = emptyMap(),
    /** Rule ID (or its config key) to its rank in the per-MR budget plan; higher is funded first. */
    @PreserveKeys
    val priorities: Map<String, Int> = emptyMap(),
    @PreserveKeys
    val providers: Map<String, JsonElement> = emptyMap(),
    @PreserveKeys
    val ruleProviders: Map<String, String> = emptyMap(),
    @PreserveKeys
    val hooks: Map<String, AiHookConfig> = emptyMap(),
    @SerialName("rules")
    @PreserveKeys
    val ruleStrategies: Map<String, AiRuleStrategyConfig> = emptyMap(),
    val concurrency: Int = MAX_CONCURRENCY,
    val executionTimeout: KarandashDuration? = null,
    @PreserveKeys
    val context: Map<String, String> = emptyMap(),
    val reviewMode: DiffMode = DiffMode.CHANGED,
    @SerialName("dryRun")
    val dryRunAi: Boolean = false,
    @PreserveKeys
    val systemPrompts: Map<String, JsonElement> = emptyMap(),
    val scopes: List<ScopeDefinition> = emptyList(),
    /** `ai.review`: path-scoped project review instructions, sent as T0 when their paths match. */
    val review: ReviewConfig = ReviewConfig(),
    /** `ai.learnings`: the `calibrate --learn` file read from the MR base, sent as T0 and applied to the findings. */
    val learnings: LearningsConfig = LearningsConfig(),
    val crossFileContextMaxChars: Int = CrossFileContextBuilder.DEFAULT_MAX_CONTEXT_CHARS,
    val feedbackEnabled: Boolean = false,
    /**
     * `ai.feedback.skipRepeatFindingTypes`: skip a rule outright once its findings were dismissed
     * [FeedbackStore.REPEAT_DISMISSAL_THRESHOLD] times or more.
     */
    val feedbackSkipRepeatFindingTypes: Boolean = false,
    /** `ai.feedback.replyJudge`: who decides replies the keyword classifier leaves unknown ([ReplyJudgeMode]). */
    val feedbackReplyJudge: ReplyJudgeMode = ReplyJudgeMode.OFF,
    /** Two or more: each custom AI rule runs once per model and keeps the findings [consensusStrategy] agrees on. */
    val consensusModels: List<String> = emptyList(),
    /** How many [consensusModels] must report a finding: `intersection` (all), `majority`, `first_agreement` (two). */
    val consensusStrategy: String = "intersection",
    /** Also vote on the built-in analyzers that review the diff (not MR-level ones); off by default (cost). */
    val analyzerConsensus: Boolean = false,
    /** Model id (or id prefix) to USD per million tokens; overrides [AiCostEstimator.DEFAULT_PRICING]. */
    @PreserveKeys
    val pricing: Map<String, ModelPrice> = emptyMap(),
    /**
     * `ai.promptCacheTtl`: lifetime of the Anthropic prompt-cache entries (`5m` or `1h`). `1h` costs 2x per cache write
     * instead of 1.25x and pays off when runs sharing a system prompt start 5-60 minutes apart.
     */
    val promptCacheTtl: String = AiCostEstimator.PROMPT_CACHE_TTL_5M,
    /**
     * `ai.reasoningEffort` (unset by default): sent as OpenRouter's `reasoning: {effort}` by OpenAI-compatible
     * providers, so a reasoning model (`low`, `minimal`, `none`) leaves room in the output cap for the reply.
     */
    val reasoningEffort: String? = null,
    /**
     * `ai.sharedSystemPrompt` (default off): every analyzer gets one short common system prompt, and its own
     * instructions move into the user turn after the shared MR context, so the cached prefix is shared across analyzers.
     */
    val sharedSystemPrompt: Boolean = false,
    val filter: FilterConfig? = null,
    val enricher: EnricherConfig = EnricherConfig(),
    val grounding: GroundingConfig = GroundingConfig(),
    /** `ai.ranking`: score weights and protect conditions of the AI finding caps, see [RankingConfig]. */
    val ranking: RankingConfig = RankingConfig(),
    /** `ai.dedup`: cross-rule in-run dedup of AI findings, see [DedupConfig]. */
    val dedup: DedupConfig = DedupConfig(),
    /** `ai.review-comments.samples` / `.sample-temperature`, lifted out of the review-comments block. */
    val reviewSampling: ReviewSamplingConfig = ReviewSamplingConfig(),
    /** `ai.agreement`: matching of review-comments samples, see [AgreementConfig]. */
    val agreement: AgreementConfig = AgreementConfig(),
    /** `ai.hints`: the deterministic hint block of review-comments, see [HintsConfig]. */
    val hints: HintsConfig = HintsConfig(),
    val constitution: ConstitutionConfig = ConstitutionConfig(),
    val gates: List<GateConfig> = emptyList(),
    val enforcementSchedule: List<EnforcementPhase> = emptyList(),
    val severityOverrides: List<SeverityOverride> = emptyList(),
    val gracePeriods: List<GracePeriodConfig> = emptyList(),
    val conditionalSections: List<ConditionalSection> = emptyList(),
    val compositions: List<CompositionRule> = emptyList(),
    val seniorityTiers: List<SeniorityTier> = emptyList(),
    val costBudget: CostBudgetConfig? = null,
    val executionProfile: String? = null,
    @PreserveKeys
    val executionProfiles: Map<String, ExecutionProfileConfig> = emptyMap(),
    val tracks: List<TrackConfig> = emptyList(),
    val findingTemplate: String? = null,
    val reasoning: String = "direct",
    /** Per-analyzer `ai.<analyzer>.reasoning` overrides of [reasoning], keyed as written. */
    @PreserveKeys
    val reasonings: Map<String, String> = emptyMap(),
    /** Per-analyzer `ai.<analyzer>.max-tokens` token budgets, keyed as written; absent = the analyzer's estimate. */
    @PreserveKeys
    val analyzerMaxTokens: Map<String, Int> = emptyMap(),
    val violationBudgets: List<ViolationBudgetConfig> = emptyList(),
    val notifications: List<NotificationRoute> = emptyList(),
    val pgoEnabled: Boolean = false,
    val teams: List<TeamConfig> = emptyList(),
    @PreserveKeys
    val canary: Map<String, CanaryConfig> = emptyMap(),
    val adrs: List<AdrConstraint> = emptyList(),
    @PreserveKeys
    val macroDefinitions: Map<String, MacroDefinition> = emptyMap(),
    val macroReferences: List<MacroReference> = emptyList(),
    val analyzer: AiAnalyzerConfig = AiAnalyzerConfig(),
    val defectPredictor: DefectPredictorConfig = DefectPredictorConfig(),
    val pipeline: AiPipelineConfig = AiPipelineConfig(),
    val statistics: AiStatisticsConfig = AiStatisticsConfig(),
    val research: AiResearchConfig = AiResearchConfig(),
    /** `ai.structuredOutput` (default on): constrain analyzer replies to their result schema where the provider supports it. */
    val structuredOutput: Boolean = true,
    /** Per-analyzer `ai.<analyzer>.structuredOutput` overrides of [structuredOutput], keyed as written. */
    @PreserveKeys
    val structuredOutputs: Map<String, Boolean> = emptyMap(),
    /** `ai.correlation`: merge same-location findings across analyzers and add configured compound-risk patterns. */
    val correlation: CorrelationConfig? = null,
    /** `ai.contradictions`: turn contradiction removal off or pick how a contradictory pair is resolved. */
    val contradictions: ContradictionsConfig? = null,
    /** `ai.budget`: an explicit `max-input-tokens` ceiling over [contextBudget], output reserve and T2 weights. */
    val budget: AiBudgetConfig = AiBudgetConfig(),
    /**
     * `ai.context.budget`: input tokens per request, null for `auto` (see [ContextBudget]). Read from the [context]
     * block in [fromPluginConfig], so it is not a key of its own here.
     */
    @Transient
    val contextBudget: Int? = null,
    /** `ai.context.stripImports` (default on): leave import/package lines out of the windowed full-file context. */
    @Transient
    val contextStripImports: Boolean = true,
    /** `ai.context.importGraph` (default on): add the import graph to the cross-file context. */
    @Transient
    val contextImportGraph: Boolean = true,
    /** `ai.debug`: `prompt: artifact` dumps each sent request to files (see [AiDebugConfig]). */
    val debug: AiDebugConfig = AiDebugConfig(),
    /** `ai.ollama.allowHosts`: extra hosts the Ollama provider may reach (see [AiOllamaConfig]). */
    val ollama: AiOllamaConfig = AiOllamaConfig(),
    /**
     * `ai.stream` (default off): ask the provider for a streamed reply, so `ai.timeout` bounds only the silence
     * between chunks and a long generation that keeps sending is not cut off. Usage comes from the last chunk.
     */
    val stream: Boolean = false,
    /** `ai.streamDeadline` (default 5m): longest a streamed call may run in total before it is retried. */
    @Transient
    val streamDeadline: KarandashDuration = DEFAULT_STREAM_DEADLINE,
    /** `ai.streamStall` (default 60s, at most `ai.timeout`): longest silence between two chunks of a streamed call. */
    @Transient
    val streamStall: KarandashDuration = DEFAULT_STREAM_STALL,
    /** `ai.stub.replyFile`: canned replies for `ai.provider: stub` (see [AiStubConfig]). */
    val stub: AiStubConfig = AiStubConfig(),
    /** `ai.chunking`: chunk size, overlap and pass count for large diffs; absent = the `ai.limits` chunking. */
    val chunking: ChunkingConfig? = null,
    /** `ai.incremental`: `enabled: false` reviews the whole diff on every run instead of only the delta. */
    val incremental: IncrementalConfig? = null,
    /** `ai.large-mr`: oversized-MR threshold, chunk parallelism and incremental override (see [AiLargeMrConfig]). */
    val largeMr: AiLargeMrConfig = AiLargeMrConfig(),
    /** `ai.testGap.level`: whether a test gap is judged over the whole MR or per review unit (see [TestGapConfig]). */
    val testGap: TestGapConfig = TestGapConfig(),
    /** `ai.thread-commands`: whether `@karandash ask` is answered (see [ThreadCommandsConfig]). */
    val threadCommands: ThreadCommandsConfig = ThreadCommandsConfig(),
    /** `ai.checks`: pre-merge checks answered pass, fail or n/a in one call and shown as a checklist. */
    val checks: List<PreMergeCheck> = emptyList(),
    /**
     * `ai.checks-ticket-compliance`: add a checklist row per acceptance criterion of the linked ticket (Jira,
     * GitHub, GitLab enrichment). Off by default: its precision is unmeasured.
     */
    val checksTicketCompliance: Boolean = false,
    /** `ai.crossFileContext`: add related repository files (imports, tests) to the prompt. */
    val crossFileContext: CrossFileContextConfig? = null,
    /** `ai.processRisk`: score process risk and report it as an INFO finding. */
    val processRisk: ProcessRiskConfig? = null,
    /** `ai.routing`: cheap-model triage, escalating to the strong tier by process risk. */
    val routing: RoutingConfig? = null,
    /** `ai.inlineOverrides`: `@karandash override <rule>: <reason>` description tags. */
    val inlineOverrides: InlineOverridesConfig? = null,
    /** `ai.gracePeriod`: approval-gated grace window opened by a description tag. */
    val gracePeriod: GracePeriodWindowConfig? = null,
    /** `ai.violationBudget`: per-file baseline of tolerated findings. */
    val violationBudget: ViolationBaselineConfig? = null,
    /** `ai.temporal`: cross-MR repeat-violation escalation with a memory window and factor. */
    val temporal: TemporalConfig? = null,
    /** `ai.calibration`: end-of-run rule calibration report from the statistics history. */
    val calibration: CalibrationConfig? = null,
    /** `ai.sandbox`: `karandash test-rule` defaults. */
    val sandbox: SandboxConfig? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @SerialName("context-lines")
    @JsonNames("contextLines")
    val contextLines: Int = FetchOptions.DEFAULT_CONTEXT_LINES,
) : PipelineExtensionConfig {
    companion object {
        const val MAX_CONCURRENCY = 20
        const val DEFAULT_TOKEN_BUDGET = 50_000

        private const val KEY_FEATURES = "features"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODELS = "models"
        private const val KEY_TEMPERATURES = "temperatures"
        private val TEMPERATURE_RANGE = 0.0..2.0
        private const val KEY_REASONING = "reasoning"
        private const val KEY_REASONINGS = "reasonings"
        private const val KEY_STRUCTURED_OUTPUT = "structuredOutput"
        private const val KEY_STRUCTURED_OUTPUTS = "structuredOutputs"
        private const val KEY_MAX_TOKENS = "maxTokens"
        private const val KEY_PRIORITY = "priority"
        private const val KEY_PRIORITIES = "priorities"
        private const val KEY_ANALYZER_MAX_TOKENS = "analyzerMaxTokens"
        private const val KEY_ANALYZER = "analyzer"
        private const val KEY_MAX_COMMENTS = "maxComments"
        private const val KEY_MAX_COMMENTS_HARD = "maxCommentsHard"
        private const val KEY_TOKENS = "tokens"
        private const val KEY_CONTEXT = "context"
        private const val KEY_BUDGET = "budget"
        private const val KEY_STRIP_IMPORTS = "stripImports"
        private const val KEY_STREAM_DEADLINE = "streamDeadline"
        private const val KEY_STREAM_STALL = "streamStall"

        /** `ai.streamDeadline` default. */
        val DEFAULT_STREAM_DEADLINE: KarandashDuration = KarandashDuration.ofMinutes(5)

        /** `ai.streamStall` default. */
        val DEFAULT_STREAM_STALL: KarandashDuration = KarandashDuration.ofSeconds(60)
        private const val KEY_IMPORT_GRAPH = "importGraph"
        private const val KEY_SAMPLES = "samples"
        private const val KEY_SAMPLE_TEMPERATURE = "sampleTemperature"
        private const val KEY_REVIEW_SAMPLING = "reviewSampling"

        /**
         * Known top-level AI config keys (as opposed to per-sub-rule feature keys).
         * Used by [topLevelAiBlockToPluginConfigElement] to separate shared config from sub-rule entries; public so
         * the configuration schema doc can be checked against it.
         */
        val TOP_LEVEL_KEYS: Set<String> by lazy {
            val d = serializer().descriptor
            val serialized = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
            serialized + setOf(
                // read by the config parser: `enabled: false` drops the whole block
                "enabled",
                "baseUrl",
                "timeout",
                KEY_STREAM_DEADLINE,
                KEY_STREAM_STALL,
                "maxRetries",
                "limits",
                "thresholds",
                KEY_MAX_COMMENTS,
                KEY_MAX_COMMENTS_HARD,
                "responseLanguage",
                "splitThreshold",
                "filters",
                "ruleFilters",
                "customRules",
                "feedback",
                "consensus",
                "codeQualityIncludeMaturityLevel",
                "size-policy",
                "sizePolicy",
                "mode",
                KEY_PRIORITY,
                "concernArea",
                "contextLines",
                "contextQuality",
                "conventionContext",
                // read from the raw `ai` block by the metrics enricher, not analyzer blocks
                "cloneDetection",
                "naturalness",
                // read from the raw `ai` block by the pipeline extension's architecture checks
                "architecture",
                "dependencies",
                "mutationTesting",
                // read by ConfigMapParser into Config.ai enrichment (generate description/title)
                "enrichment",
            ) + AiConfigAliases.KEYS
        }

        /** Per-analyzer sub-keys that [topLevelAiBlockToPluginConfigElement] carries over. */
        private val SUB_RULE_KEYS: Set<String> = setOf(
            KEY_ENABLED,
            "model",
            "temperature",
            KEY_REASONING,
            KEY_STRUCTURED_OUTPUT,
            KEY_MAX_TOKENS,
            "max-tokens",
            "mode",
            KEY_PRIORITY,
            "concernArea",
            "min-severity",
            "minSeverity",
            KEY_MAX_COMMENTS,
            KEY_MAX_COMMENTS_HARD,
            KEY_SAMPLES,
            KEY_SAMPLE_TEMPERATURE,
        )

        /**
         * Converts a top-level `ai:` config block [JsonElement] into the flat
         * plugin config [JsonElement] expected by [fromPluginConfig].
         *
         * Sub-rule entries (any object-valued key not in [TOP_LEVEL_KEYS]) are decomposed into
         * `features`, `models`, and `temperatures` maps. Existing values for those maps are
         * preserved; sub-rule entries take precedence on conflict.
         */
        fun topLevelAiBlockToPluginConfigElement(
            raw: JsonElement,
            logger: Logger? = null,
        ): JsonElement {
            val obj = raw as? JsonObject ?: return raw
            val result = mutableMapOf<String, JsonElement>()
            val features = mutableMapOf<String, JsonElement>()
            val models = mutableMapOf<String, JsonElement>()
            val temperatures = mutableMapOf<String, JsonElement>()
            val reasonings = mutableMapOf<String, JsonElement>()
            val structuredOutputs = mutableMapOf<String, JsonElement>()
            val maxTokens = mutableMapOf<String, JsonElement>()
            val priorities = mutableMapOf<String, JsonElement>()
            val dropped = mutableListOf<String>()
            val reviewCaps = mutableMapOf<String, JsonElement>()
            val reviewSampling = mutableMapOf<String, JsonElement>()

            for ((key, value) in obj) {
                if (key in TOP_LEVEL_KEYS) {
                    result[key] = value
                } else if (value is JsonObject) {
                    value.keys.filter { it !in SUB_RULE_KEYS }.forEach { dropped += "$key.$it" }
                    val enabledVal = value[KEY_ENABLED]
                    if (enabledVal != null) features[key] = enabledVal
                    value["model"]?.let { models[key] = it }
                    value["temperature"]?.let { temperatures[key] = it }
                    value[KEY_REASONING]?.let { reasonings[key] = it }
                    value[KEY_STRUCTURED_OUTPUT]?.let { structuredOutputs[key] = it }
                    (value[KEY_MAX_TOKENS] ?: value["max-tokens"])?.let { maxTokens[key] = it }
                    value[KEY_PRIORITY]?.let { if (it.isInt()) priorities[key] = it else dropped += "$key.$KEY_PRIORITY (not an integer)" }
                    // review-comments owns the MR-wide comment cap; an explicit top-level maxComments wins
                    if (key == "reviewComments" || key == "review-comments") {
                        listOf(KEY_MAX_COMMENTS, KEY_MAX_COMMENTS_HARD).forEach { cap -> value[cap]?.let { reviewCaps[cap] = it } }
                        listOf(KEY_SAMPLES, KEY_SAMPLE_TEMPERATURE).forEach { k -> value[k]?.let { reviewSampling[k] = it } }
                    }
                } else {
                    result[key] = value
                }
            }
            if (dropped.isNotEmpty()) {
                logger?.warn(
                    "AI plugin: ignoring unsupported ai: key(s): ${dropped.joinToString(", ")}. " +
                        "Per-analyzer blocks honour enabled/model/temperature/reasoning/structuredOutput/max-tokens/mode/priority/concernArea/maxComments/maxCommentsHard/samples/sampleTemperature (review-comments).",
                )
            }

            reviewCaps.forEach { (cap, value) -> if (cap !in result) result[cap] = value }
            if (reviewSampling.isNotEmpty()) result[KEY_REVIEW_SAMPLING] = JsonObject(reviewSampling)

            applySuggestionConfig(obj, result, features)

            val existingFeatures = result[KEY_FEATURES] as? JsonObject ?: JsonObject(emptyMap())
            result[KEY_FEATURES] = JsonObject(existingFeatures + features)
            result.mergeEntries(KEY_MODELS, models)
            result.mergeEntries(KEY_TEMPERATURES, temperatures)
            if (reasonings.isNotEmpty()) result[KEY_REASONINGS] = JsonObject(reasonings)
            if (structuredOutputs.isNotEmpty()) result[KEY_STRUCTURED_OUTPUTS] = JsonObject(structuredOutputs)
            if (maxTokens.isNotEmpty()) result[KEY_ANALYZER_MAX_TOKENS] = JsonObject(maxTokens)
            result.mergeEntries(KEY_PRIORITIES, priorities)
            return JsonObject(result)
        }

        /** Sub-rule [entries] over the top-level map at [key]; sub-rule entries win on conflict. */
        private fun MutableMap<String, JsonElement>.mergeEntries(
            key: String,
            entries: Map<String, JsonElement>,
        ) {
            if (entries.isNotEmpty()) this[key] = JsonObject((this[key] as? JsonObject).orEmpty() + entries)
        }

        private fun JsonElement.isInt(): Boolean = this is JsonPrimitive && !isString && intOrNull != null

        /**
         * Synthesizes the suggestion keys of [result] from the `code-suggestions` sub-block, or promotes the
         * old ones from `review-comments` when that block is absent.
         */
        private fun applySuggestionConfig(
            obj: JsonObject,
            result: MutableMap<String, JsonElement>,
            features: MutableMap<String, JsonElement>,
        ) {
            // the code-suggestions analyzer runs its own call; `mode: inline` (default) also keeps the
            // suggestion-enhanced review-comments prompt, `mode: separate` leaves review-comments without it.
            val csKey = when {
                obj.containsKey("codeSuggestions") -> "codeSuggestions"
                obj.containsKey(AiRuleIds.CODE_SUGGESTIONS) -> AiRuleIds.CODE_SUGGESTIONS
                else -> null
            }
            val csBlock = csKey?.let { obj[it] as? JsonObject }
            if (csBlock != null) {
                val csEnabled = csBlock[KEY_ENABLED]?.jsonPrimitive?.booleanOrNull != false
                val csMode = csBlock["mode"]?.jsonPrimitive?.content ?: CodeSuggestionLimits.DEFAULT_MODE
                result[KEY_INCLUDE_FIX_SUGGESTIONS] = JsonPrimitive(csEnabled && csMode == CodeSuggestionLimits.DEFAULT_MODE)
                result[KEY_CODE_SUGGESTION_MODE] = JsonPrimitive(csMode)
                val csMinSeverity = csBlock["min-severity"]?.jsonPrimitive?.content
                    ?: csBlock["minSeverity"]?.jsonPrimitive?.content
                if (csMinSeverity != null) {
                    result[KEY_FIX_SUGGESTION_MIN_SEVERITY] = JsonPrimitive(csMinSeverity)
                }
                // Presence of code-suggestions block implies enabled unless explicitly false
                if (csEnabled && !features.containsKey(csKey)) {
                    features[csKey] = JsonPrimitive(true)
                }
            }
        }

        /**
         * Builds an [AiPluginConfig] from a [JsonElement].
         *
         * Pre-processes the element for fields that need special handling (polymorphic
         * values, value clamping) then deserializes via [deserializeConfig].
         */
        fun fromPluginConfig(
            config: JsonElement,
            logger: Logger? = null
        ): AiPluginConfig {
            val obj = config as? JsonObject ?: JsonObject(emptyMap())
            val processed = withValidAnalyzerTokens(preProcessConfig(AiConfigAliases.normalize(obj), logger), logger)
            val parsed = deserializeConfig<AiPluginConfig>(processed)
            PreMergeCheck.warnUnknownModes(processed["checks"], logger)
            return parsed.copy(
                concurrency = parsed.concurrency.coerceIn(1, MAX_CONCURRENCY),
                crossFileContextMaxChars = parsed.crossFileContextMaxChars.coerceAtLeast(0),
                chunking = parsed.chunking?.normalized(),
                crossFileContext = parsed.crossFileContext?.let {
                    it.copy(maxContextFiles = it.maxContextFiles.coerceAtLeast(0))
                },
                contextLines = parsed.contextLines.coerceIn(
                    FetchOptions.MIN_CONTEXT_LINES,
                    FetchOptions.MAX_CONTEXT_LINES,
                ),
                scopes = resolveScopes(obj),
                largeMr = parsed.largeMr.validated(logger),
                testGap = parsed.testGap.validated(),
                grounding = parsed.grounding.validated(),
                ranking = parsed.ranking.validated(),
                dedup = parsed.dedup.validated(),
                reviewSampling = parsed.reviewSampling.validated(),
                agreement = parsed.agreement.validated(),
                hints = parsed.hints.validated(),
                contextBudget = ContextBudget.parse((obj[KEY_CONTEXT] as? JsonObject)?.get(KEY_BUDGET), logger),
                contextStripImports = contextFlag(obj, KEY_STRIP_IMPORTS),
                contextImportGraph = contextFlag(obj, KEY_IMPORT_GRAPH),
                streamDeadline = positiveDuration(obj, KEY_STREAM_DEADLINE) ?: DEFAULT_STREAM_DEADLINE,
                streamStall = positiveDuration(obj, KEY_STREAM_STALL) ?: DEFAULT_STREAM_STALL,
            )
        }

        /** `ai.<key>` as a positive duration (`90s`, `5m`), null when unset; a [ConfigParseException] naming the key otherwise. */
        private fun positiveDuration(
            obj: JsonObject,
            key: String,
        ): KarandashDuration? {
            val raw = obj[key] ?: return null
            val text = (raw as? JsonPrimitive)?.contentOrNull
            val parsed =
                try {
                    text?.let(KarandashDuration::parse)
                } catch (e: IllegalStateException) {
                    throw ConfigParseException("ai.$key: '$raw' is not a duration (${e.message})")
                }
            return parsed?.takeIf { it.millis > 0 } ?: throw ConfigParseException("ai.$key: '$raw' is not a positive duration")
        }

        /** `ai.context.<key>` as a boolean, default true; a [ConfigParseException] naming the key otherwise. */
        private fun contextFlag(
            obj: JsonObject,
            key: String
        ): Boolean {
            val raw = (obj[KEY_CONTEXT] as? JsonObject)?.get(key) ?: return true
            return (raw as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
                ?: throw ConfigParseException("ai.context.$key: '$raw' is not true or false")
        }

        /** Replaces `analyzer.tokens` with its checked form (reported once here) so the decode below cannot throw on it. */
        private fun withValidAnalyzerTokens(
            config: JsonObject,
            logger: Logger?,
        ): JsonObject {
            val analyzer = config[KEY_ANALYZER] as? JsonObject ?: return config
            val tokens = analyzer[KEY_TOKENS] ?: return config
            val checked = KarandashJson.Default.encodeToJsonElement(
                AiAnalyzerTokensConfig.serializer(),
                AiAnalyzerTokensConfig.parse(tokens, logger),
            )
            return JsonObject(config + (KEY_ANALYZER to JsonObject(analyzer + (KEY_TOKENS to checked))))
        }

        /**
         * Applies pre-processing to a [JsonObject] for fields that have polymorphic
         * or non-standard representations before deserialization.
         */
        private fun preProcessConfig(
            config: JsonObject,
            logger: Logger?
        ): JsonObject {
            val mutable = config.toMutableMap()

            // dryRun: normalize polymorphic value (Boolean | Object with scope) to Boolean
            mutable["dryRun"] = JsonPrimitive(parseDryRun(config["dryRun"]))

            // context: coerce non-string primitive values to string; `budget` is read by [ContextBudget], not a template variable
            val rawContext = config[KEY_CONTEXT] as? JsonObject
            if (rawContext != null) {
                mutable[KEY_CONTEXT] = JsonObject(
                    (rawContext - KEY_BUDGET - KEY_STRIP_IMPORTS - KEY_IMPORT_GRAPH).mapValues { (_, v) ->
                        JsonPrimitive(v.jsonPrimitive.content)
                    }
                )
            }

            // ruleStrategies: filter to only entries that contain "strategy" or "covers"
            val rawStrategies = config["rules"] as? JsonObject
            if (rawStrategies != null) {
                mutable["rules"] = JsonObject(
                    rawStrategies.filter { (_, value) ->
                        val sub = value as? JsonObject ?: return@filter false
                        sub.containsKey("strategy") || sub.containsKey("covers")
                    }
                )
            }

            // hooks: filter to only entries that contain a "prompt" key
            val rawHooks = config["hooks"] as? JsonObject
            if (rawHooks != null) {
                mutable["hooks"] = JsonObject(
                    rawHooks.filter { (_, value) ->
                        val sub = value as? JsonObject ?: return@filter false
                        sub.containsKey("prompt")
                    }
                )
            }

            // feedback object form: { enabled, skipRepeatFindingTypes, replyJudge, dismissed: [...] }
            val rawFeedback = config["feedback"] as? JsonObject
            if (rawFeedback != null) {
                rawFeedback[KEY_ENABLED]?.let { if ("feedbackEnabled" !in config) mutable["feedbackEnabled"] = it }
                rawFeedback["skipRepeatFindingTypes"]?.let { mutable["feedbackSkipRepeatFindingTypes"] = it }
            }
            // flat feedbackReplyJudge wins over feedback.replyJudge, like feedbackEnabled; an unknown mode is off
            (config["feedbackReplyJudge"] ?: rawFeedback?.get("replyJudge"))?.let { raw ->
                val value = (raw as? JsonPrimitive)?.contentOrNull
                val known = ReplyJudgeMode.entries.any { it.name.equals(value, ignoreCase = true) }
                if (!known) logger?.warn("ai.feedback.replyJudge: '$raw' unknown, using off (expected off or llm)")
                mutable["feedbackReplyJudge"] = if (known) raw else JsonPrimitive(ReplyJudgeMode.OFF.name)
            }

            // ruleProviders: filter empty string values
            val rawRuleProviders = config["ruleProviders"] as? JsonObject
            if (rawRuleProviders != null) {
                mutable["ruleProviders"] = JsonObject(
                    rawRuleProviders
                        .mapValues { (_, v) -> JsonPrimitive(v.jsonPrimitive.content) }
                        .filter { (_, v) -> v.content.isNotEmpty() }
                )
            }

            // promptOverrides: coerce non-string values to string
            val rawPromptOverrides = config["promptOverrides"] as? JsonObject
            if (rawPromptOverrides != null) {
                mutable["promptOverrides"] = JsonObject(
                    rawPromptOverrides.mapValues { (_, v) -> JsonPrimitive(v.jsonPrimitive.content) }
                )
            }

            // models: coerce non-string values to string
            val rawModels = config[KEY_MODELS] as? JsonObject
            if (rawModels != null) {
                mutable[KEY_MODELS] = JsonObject(
                    rawModels.mapValues { (_, v) -> JsonPrimitive(v.jsonPrimitive.content) }
                )
            }

            // temperatures: keep numbers in TEMPERATURE_RANGE, drop (and warn about) anything else
            val rawTemps = config[KEY_TEMPERATURES] as? JsonObject
            if (rawTemps != null) {
                mutable[KEY_TEMPERATURES] = JsonObject(
                    rawTemps.filter { (k, v) ->
                        val d = (v as? JsonPrimitive)?.doubleOrNull
                        (d != null && d in TEMPERATURE_RANGE).also {
                            if (!it) logger?.warn("ai.temperatures.$k: '$v' ignored, expected a number in 0.0..2.0")
                        }
                    }
                )
            }

            // analyzerMaxTokens: keep positive whole numbers, drop (and warn about) anything else
            (config[KEY_ANALYZER_MAX_TOKENS] as? JsonObject)?.let { raw ->
                mutable[KEY_ANALYZER_MAX_TOKENS] = JsonObject(
                    raw.filter { (k, v) ->
                        val n = (v as? JsonPrimitive)?.intOrNull
                        (n != null && n > 0).also {
                            if (!it) logger?.warn("ai.$k.max-tokens: '$v' ignored, expected a positive whole number")
                        }
                    }
                )
            }

            // `correlation: true` / `contradictions: false` shorthand for `{ enabled: ... }`
            BOOLEAN_BLOCK_KEYS.forEach { key ->
                (config[key] as? JsonPrimitive)?.let { mutable[key] = JsonObject(mapOf(KEY_ENABLED to it)) }
            }

            // scopes: handled by post-processing; remove to avoid deserialization issues
            // since scopes config is a named map but the data class expects a list
            mutable.remove("scopes")

            return JsonObject(mutable)
        }

        private fun resolveScopes(config: JsonObject): List<ScopeDefinition> {
            val raw = config["scopes"] as? JsonObject ?: return emptyList()
            return raw.mapNotNull { (name, value) ->
                val map = value as? JsonObject ?: return@mapNotNull null
                val paths = (map["paths"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.takeIf { p -> p.isString }?.content }
                    ?.ifEmpty { null }
                    ?: return@mapNotNull null
                val instructions = map["instructions"]?.let {
                    KarandashJson.Default.decodeFromJsonElement<ContentSource>(it)
                }
                val practices = map["bestPractices"]?.let {
                    KarandashJson.Default.decodeFromJsonElement<ContentSource>(it)
                }
                ScopeDefinition(
                    name = name,
                    paths = paths,
                    instructions = instructions,
                    bestPractices = practices,
                )
            }
        }

        private const val KEY_INCLUDE_FIX_SUGGESTIONS = "includeFixSuggestions"
        private val BOOLEAN_BLOCK_KEYS = listOf(
            "calibration",
            "correlation",
            "contradictions",
            "chunking",
            "incremental",
            "crossFileContext",
            "processRisk",
            "routing",
            "inlineOverrides",
            "gracePeriod",
            "violationBudget",
            "temporal",
        )
        private const val KEY_FIX_SUGGESTION_MIN_SEVERITY = "fixSuggestionMinSeverity"
        private const val KEY_CODE_SUGGESTION_MODE = "codeSuggestionMode"

        private fun parseDryRun(raw: JsonElement?): Boolean =
            when (raw) {
                is JsonPrimitive -> {
                    raw.booleanOrNull ?: false
                }

                is JsonObject -> {
                    val scope = raw["scope"]?.jsonPrimitive?.content ?: "all"
                    scope.equals("ai", ignoreCase = true) || scope.equals("all", ignoreCase = true)
                }

                else -> {
                    false
                }
            }
    }
}

/**
 * Configuration for a single AI hook.
 *
 * @param provider Optional provider override (uses default if null).
 * @param prompt Prompt template with `{{variable}}` placeholders.
 * @param action What to do with the AI response.
 * @param model Optional model override for this hook.
 */
@Serializable
data class AiHookConfig(
    val provider: String? = null,
    val prompt: String,
    val action: AiHookAction = AiHookAction.LOG,
    val model: String? = null,
)

/** Action to perform with the AI hook response. */
@Serializable(with = AiHookActionSerializer::class)
enum class AiHookAction {
    /** Post AI response as an MR comment. */
    COMMENT,

    /** Store the AI response in the metadata of the findings the hook is about. */
    METADATA,

    /** Output AI response as diagnostic log. */
    LOG,

    /** Create a new finding from AI response. */
    FINDING;

    companion object {
        fun fromString(value: String): AiHookAction = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOG
    }
}

/**
 * Serializes [AiHookAction] as a lowercase string, deserializes case-insensitively
 * with [AiHookAction.LOG] as fallback for unknown values.
 */
internal object AiHookActionSerializer : KSerializer<AiHookAction> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AiHookAction", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: AiHookAction
    ) = encoder.encodeString(value.name.lowercase())

    override fun deserialize(decoder: Decoder): AiHookAction = AiHookAction.fromString(decoder.decodeString())
}
