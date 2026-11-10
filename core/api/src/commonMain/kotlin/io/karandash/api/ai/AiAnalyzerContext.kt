@file:OptIn(ExperimentalKarandashApi::class)

package io.karandash.api.ai

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.api.platform.MergeRequestField
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Context passed to AI analyzers during validation.
 *
 * Bundles all parameters that an analyzer needs into a single object,
 * reducing parameter count and making it easy to add new context
 * without breaking existing signatures.
 *
 * Not @Serializable: runtime context holder, carries the live [AiService] and token budget.
 */
public data class AiAnalyzerContext(
    /** The (already redacted) merge request to analyze. */
    val mr: MergeRequest,
    /** Wired AI service for issuing completion calls. */
    val aiService: AiService,
    /** Token budget for this validation run. */
    val budget: AiTokenBudget,
    /** Model identifier to use for completions. */
    val model: String,
    /** Rule-level configuration from the user's config file as [JsonElement]. */
    val config: JsonElement = JsonObject(emptyMap()),
    /** Extra instructions assembled from scopes, language overlays, and enrichers. */
    val extraInstructions: String? = null,
    /** Resolved prompt from the prompt pipeline (overrides builtin when non-null). */
    val resolvedPrompt: String? = null,
    /** Findings accumulated so far in this validation run (for cross-rule context). */
    val accumulatedFindings: List<Finding> = emptyList(),
    /** Logger for analyzer-level debug output. */
    val logger: Logger = NoOpLogger,
    /** Pre-built diff and metadata context shared across all analyzers in this run. */
    val diffContext: PrecomputedDiffContext = PrecomputedDiffContext(),
    /** Receives the reason when the analyzer declines to run; the host discloses the rule as skipped. Use [skip]. */
    val onSkip: (reason: String) -> Unit = {},
    /**
     * The MR context the host shares across analyzers (tickets, conventions, enricher blocks, cross-file
     * context), in prompt order; each block's [AiContextBlock.content] is the text as sent and its
     * [AiContextBlock.source] the part or enricher id. The host already puts these ahead of every request's
     * own [AiCompletionRequest.contextBlocks] through [aiService], so an analyzer reads them (for example to
     * see whether a design doc arrived) and must not send them again.
     */
    val contextBlocks: List<AiContextBlock> = emptyList(),
    /**
     * Merge-request fields the run's platform never fills
     * ([io.karandash.api.module.PlatformServiceModule.unavailableMergeRequestFields]); an empty value of one of them
     * means "unknown", not "none". The host already notes them in the shared context. Default: empty.
     */
    @ExperimentalKarandashApi
    val unavailableFields: Set<MergeRequestField> = emptySet(),
) {
    /**
     * Declines this run: logs [reason] at debug and reports it through [onSkip], so the rule shows as skipped
     * (for example "token budget insufficient") instead of "ran, no findings".
     *
     * @return an empty finding list, so an analyzer can `return context.skip("...")`.
     */
    public fun skip(reason: String): List<Finding> {
        logger.debug("Skipped: $reason")
        onSkip(reason)
        return emptyList()
    }
}
