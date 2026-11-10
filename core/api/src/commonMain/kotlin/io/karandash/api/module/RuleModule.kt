package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.StableKarandashApi
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.run.RunContext
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

@StableKarandashApi
public interface RuleModule : Module {
    override val type: ModuleType get() = ModuleType.RULE

    public suspend fun contributedRules(): Set<String>

    /**
     * Resources this module consumed so far (LLM tokens, requests, cost), or `null` when it
     * does not track usage. Read by the host after validation for cost summaries.
     */
    @ExperimentalKarandashApi
    public val usage: ModuleUsage? get() = null

    /**
     * Returns a human-readable explanation for a specific rule ID.
     * Used by reporters with --explain mode to show why a rule exists.
     *
     * @param ruleId The rule identifier to explain.
     * @return Explanation text, or null if no explanation is available.
     */
    public suspend fun ruleExplanation(ruleId: String): String? = null

    /**
     * Enrichment IDs this rule can consume when available.
     *
     * The pipeline filters [EnrichmentContext] to only include data matching
     * these prefixes. Convention: use provider ID prefixes (e.g., `"jira"`)
     * to receive all enrichment from that provider.
     *
     * Default: empty (rule does not use enrichment data).
     */
    public val supportedEnrichment: List<String> get() = emptyList()

    /**
     * Enrichment IDs that must be present for this rule to execute.
     *
     * If any required enrichment ID is missing (provider failed or not configured),
     * the rule is skipped with a warning. Subset of [supportedEnrichment].
     *
     * Default: empty (rule runs regardless of enrichment availability).
     */
    public val requiredEnrichment: List<String> get() = emptyList()

    /**
     * Merge-request fields rule [ruleId] cannot evaluate without. When the platform declares any of them in
     * [PlatformServiceModule.unavailableMergeRequestFields], the engine skips the rule with a warning
     * ("rule X skipped: platform Y doesn't provide Z") instead of letting it pass on an empty value.
     *
     * Default: empty (rule runs on every platform).
     */
    @ExperimentalKarandashApi
    public fun requiredMergeRequestFields(ruleId: String): Set<MergeRequestField> = emptySet()

    /**
     * [requiredMergeRequestFields] for one configured instance of rule [ruleId]: a rule whose need depends on its
     * config (e.g. approvals only when an approval key is set) overrides this one. The engine calls this overload.
     *
     * Default: [requiredMergeRequestFields] without the config.
     */
    @ExperimentalKarandashApi
    public fun requiredMergeRequestFields(
        ruleId: String,
        config: JsonElement,
    ): Set<MergeRequestField> = requiredMergeRequestFields(ruleId)

    /**
     * When true, this module's re-init plugin config is computed by a [PipelineExtension]
     * from the module's own dedicated top-level config block (e.g. AI's `ai:` block) rather
     * than derived per-rule from `rules:` entries. Set by modules that fan out to multiple
     * rule IDs from one module instance and own a dedicated top-level config block outside
     * `rules:`. Default false (the normal case).
     */
    public val usesExtensionComputedConfig: Boolean get() = false

    /**
     * This module's own per-rule time budget (e.g. AI's `ai.executionTimeout`), or null for none.
     * The engine bounds each rule of this module by the larger of this and `timeouts.ruleExecution`;
     * when only one of the two is set, that one applies. Default null.
     */
    @ExperimentalKarandashApi
    public val executionTimeout: Duration? get() = null

    /**
     * Tokens this module consumed during the current run, or null when it does not meter tokens.
     * Pipeline extensions use it to tell "skipped or budget exhausted" (0) from "ran clean".
     */
    @ExperimentalKarandashApi
    public fun tokensConsumed(): Int? = null

    /** Logs this module's end-of-run cost summary once; extensions call it after reporting. Default: no-op. */
    @ExperimentalKarandashApi
    public fun logCostSummary() {
    }

    /**
     * Overload of [Module.initialize] that also receives `rule-enrichment:` binding prefixes
     * (raw glob patterns, e.g. `"gitlab:*"`) configured for this module's own [contributedRules],
     * keyed by rule ID.
     *
     * Exists for modules that fan out to multiple independently-configured rule IDs from one
     * module instance (such as the built-in AI rule module) and need true per-rule
     * enrichment visibility that a single module-wide [io.karandash.api.context.EnrichmentContext]
     * can't express on its own. Default implementation ignores the bindings and delegates to
     * [Module.initialize], so every existing `RuleModule` implementation continues to compile
     * and behave unchanged.
     *
     * @param ruleEnrichmentBindingPrefixes Binding patterns per rule ID, scoped to this module's
     *   own [contributedRules] -- never contains entries for rules this module doesn't own.
     */
    public suspend fun initialize(
        context: PluginContext,
        ruleEnrichmentBindingPrefixes: Map<String, List<String>>,
    ): Boolean = initialize(context)

    public suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding>

    /**
     * [validate] with the current [run] (enrichment, run services). The host calls this form;
     * the default delegates to the three-argument form, so existing modules are unaffected.
     */
    @ExperimentalKarandashApi
    public suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
        run: RunContext,
    ): List<Finding> = validate(ruleId, mr, configElement)
}
