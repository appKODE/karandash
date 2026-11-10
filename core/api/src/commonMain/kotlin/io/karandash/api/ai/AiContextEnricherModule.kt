package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Enriches the context available to AI prompts with additional key-value metadata.
 *
 * Enrichers run in [enricherPriority] order (ascending, lowest first).
 * The merged map is passed to [PromptResolver][io.karandash.plugins.ai.prompts.PromptResolver]
 * as `customContext`, making values available via `{{key}}` template placeholders.
 *
 * Example use-cases: fetching JIRA ticket details, reading code metrics,
 * or querying team ownership data.
 */
public interface AiContextEnricherModule : Module {
    override val type: ModuleType get() = ModuleType.AI_CONTEXT_ENRICHER

    /** Sort priority - lower values run first. Default is 100. */
    public val enricherPriority: Int get() = 100

    /**
     * Enrichment IDs this enricher can format into a prompt block.
     *
     * Used by [AiEnricherDispatcher][io.karandash.plugins.ai.core.dispatch.AiEnricherDispatcher]
     * to route `EnrichmentContext` data to the correct formatter. Return an empty list
     * for enrichers that produce blocks from sources other than `EnrichmentContext`
     * (e.g., filesystem, subprocess).
     *
     * Default: empty (enricher is not ID-driven).
     */
    public fun handledEnrichmentIds(): List<String> = emptyList()

    /**
     * Produce enrichment key-value pairs for the given merge request.
     *
     * @param mr The merge request being validated.
     * @param context Metadata about the enrichment call.
     * @return Map of context keys to values. Empty map if nothing to add.
     */
    public suspend fun enrich(
        mr: MergeRequest,
        context: AiContextEnricherContext
    ): Map<String, String>

    /**
     * Produce a structured context block for AI prompt injection.
     *
     * Unlike [enrich] which returns key-value pairs for template substitution,
     * this returns a prioritised Markdown heading + body injected into the
     * extra-instructions section. Blocks are sorted by
     * [AiContextBlock.priority] and included within a budget; oversize blocks
     * are skipped atomically (never truncated).
     *
     * The default implementation returns null - existing enrichers need not
     * implement this. Any bridged plugin (JVM/JS/WASM/native) also gets null
     * until the bridge wires `provideBlock` through.
     *
     * @param mr The merge request being validated.
     * @param changes File changes from the MR (post-preprocessing).
     * @param config Provider-specific configuration from the `ai:` block.
     *     Callers pass [JsonNull] when no config is available.
     * @param budget Maximum character budget for the formatted block.
     *     Enrichers should self-truncate at semantic boundaries to fit within this limit.
     * @return A context block to include in the prompt, or null to skip.
     */
    public suspend fun provideBlock(
        mr: MergeRequest,
        changes: List<FileChange>,
        config: JsonElement = JsonNull,
        budget: BlockBudget = BlockBudget.UNLIMITED,
    ): AiContextBlock? = null
}

/**
 * Context passed to enrichers.
 *
 * @param ruleId The rule ID being evaluated, if known.
 * @param existingContext Context already collected from higher-priority enrichers.
 * @param enrichmentContext Enrichment data from configured enrichment providers,
 *   filtered to the enrichment IDs visible to the current rule.
 *
 * Not @Serializable: runtime context holder, carries the live [EnrichmentContext].
 */
public data class AiContextEnricherContext(
    val ruleId: String?,
    val existingContext: Map<String, String>,
    val enrichmentContext: EnrichmentContext = EnrichmentContext.EMPTY,
)
