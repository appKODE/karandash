package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.models.finding.Finding
import kotlinx.serialization.KSerializer

/**
 * Extensible AI analyzer contributed via the plugin system.
 *
 * Each analyzer adds a new AI-powered validation rule identified by [analyzerId].
 * The [AiRuleModule][io.karandash.plugins.ai.AiRuleModule] discovers registered analyzers
 * at runtime and dispatches to them when the matching rule ID is enabled.
 *
 * Analyzers receive a fully-wired [AiService] so they can issue completion calls
 * through the standard interceptor/provider chain without managing providers directly.
 */
public interface AiAnalyzerModule : Module {
    override val type: ModuleType get() = ModuleType.AI_ANALYZER

    /**
     * Rule ID this analyzer handles (e.g. "ai-my-custom-check").
     * Must be globally unique across all registered analyzers and built-in rules.
     */
    public val analyzerId: String

    /** Estimated tokens per invocation: the host budget gate skips the call, recorded in coverage, when it does not fit. */
    public val estimatedTokens: Int get() = 0

    /** Human-readable explanation of what this analyzer checks. */
    public val explanation: String get() = ""

    /**
     * camelCase config key for feature flag (e.g. "riskScoring").
     * Used by [ConfigMapParser] to map config keys to analyzer IDs.
     * Defaults to [analyzerId].
     */
    public val featureKey: String get() = analyzerId

    /** Whether this analyzer is enabled by default when the AI plugin is active. Default: false. */
    override val defaultEnabled: Boolean get() = false

    /**
     * Default system prompt used when no user override is configured.
     * Returning null means the analyzer builds its own prompt internally.
     */
    public val defaultPrompt: String? get() = null

    /** Whether this analyzer benefits from scope/language detection for extra instructions. */
    public val needsScopeDetection: Boolean get() = false

    /**
     * Whether this analyzer judges the merge request as a whole (a summary, a score, a description verdict).
     * The host then sends it the whole MR in one call instead of one call per diff chunk. Default: false.
     */
    public val mrLevel: Boolean get() = false

    /** Whether this analyzer reads `FileChange.blameData`; when it is enabled the pipeline fetches blame. */
    public val needsBlame: Boolean get() = false

    /**
     * JSON response schema description appended at the very end of the system prompt.
     *
     * When non-null, [AiRuleAnalyzerUtils.effectivePrompt] prepends common JSON format
     * discipline instructions (no markdown, no prose, proper escaping) and appends this
     * schema as the final block, so the AI receives format requirements last
     * regardless of prompt layering (builtin, custom override, or extra instructions).
     *
     * Should contain **only** the expected JSON structure, e.g.:
     * ```
     * {"comments": [{"file": "path", "line": N, "message": "text"}]}
     * ```
     */
    public val responseFormatSuffix: String? get() = null

    /**
     * Whether this analyzer supports semi-formal reasoning certificates.
     *
     * When true and the user config specifies `reasoning: semi-formal`, the dispatch
     * layer wraps this analyzer's prompt with a structured certificate template that
     * forces explicit premises, execution traces, and evidence citations.
     *
     * Analyzers that produce non-standard output formats or rely on highly specific
     * prompt structures should leave this as false.
     */
    public val supportsSemiFormalReasoning: Boolean get() = false

    /**
     * Serializer of the JSON result this analyzer decodes from the model's reply, or null when the reply has no
     * fixed shape. While `ai.structuredOutput` is on (and the rule does not set `structuredOutput: false`), the
     * host derives a JSON schema from it and attaches it to every completion request that carries none, so the
     * provider can constrain the reply to it.
     */
    public val resultSerializer: KSerializer<*>? get() = null

    /**
     * Enrichment ID prefixes this analyzer can consume via Path A (programmatic data access).
     *
     * Declared as a safety floor - the pipeline may broaden the visible set via config-side
     * `rule-enrichment` bindings. The default is empty (analyzer sees no enrichment data).
     *
     * Examples: `["jira", "github", "gitlab"]`, `["confluence"]`
     */
    public val supportedEnrichment: List<String> get() = emptyList()

    /**
     * Enrichment ID prefixes that MUST be available for this analyzer to run.
     *
     * Mirrors [io.karandash.api.module.RuleModule.requiredEnrichment] -- if any prefix here
     * matches no available enrichment ID by the time this rule is dispatched, the rule is
     * skipped with a "missing required enrichment" reason instead of running without the
     * data it depends on. Should be a subset of [supportedEnrichment] in spirit (declaring
     * something required that isn't also supported doesn't make sense).
     *
     * Default: empty (analyzer runs regardless of enrichment availability).
     */
    public val requiredEnrichment: List<String> get() = emptyList()

    /**
     * Primary analysis method - receives full context.
     *
     * @param context Full analysis context including MR, AI service, budget, and configuration.
     * @return Findings produced by the analysis (may be empty).
     */
    public suspend fun analyze(context: AiAnalyzerContext): List<Finding>
}
