package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.ReviewUnits
import io.karandash.plugins.ai.core.incremental.IncrementalContext
import io.karandash.plugins.ai.core.incremental.IncrementalValidator
import io.karandash.sdk.Logger
import kotlinx.atomicfu.atomic

/**
 * The AI incremental cache of one [AiPipelineExtension] run: decides whether cached findings can be served, marks
 * them `(cached)`, and saves the run's findings for the next one.
 *
 * @lifecycle per-run -- owned by one [AiPipelineExtension] handle
 */
internal class AiIncrementalCache(
    private val registry: ModuleRegistry,
    private val aiRule: () -> AiRuleModule?,
) {
    private val state = atomic<IncrementalCacheState?>(null)

    /** This run's [IncrementalContext], or null when incremental validation is not active. */
    val context: IncrementalContext? get() = state.value?.context

    fun reset() {
        state.value = null
    }

    /** The cached findings when the MR is fully cached, else null (a partial hit is merged later). */
    fun cachedFindings(
        mr: MergeRequest,
        pluginConfig: AiPluginConfig,
        persistence: AiStatePersistence?,
        configHash: Long,
        logger: Logger,
    ): List<Finding>? {
        // `ai.large-mr.incremental` overrides `ai.incremental.enabled` for an MR with more units than max-units.
        val largeMrOverride = pluginConfig.largeMr.incremental
            ?.takeIf { ReviewUnits.group(mr.changes).size > pluginConfig.largeMr.maxUnits }
        if ((largeMrOverride ?: pluginConfig.incremental?.enabled) == false) {
            logger.debug("Incremental: disabled by ai.${if (largeMrOverride != null) "large-mr" else "incremental.enabled"}=false")
            return null
        }
        if (persistence == null) return null
        val validator = IncrementalValidator(persistence, logger)
        val context = validator.prepare(mr, configHash)
        state.value = IncrementalCacheState(context = context, validator = validator)
        if (context.isFullyCached) {
            logger.info(
                "Cache: fully cached — ${context.unchangedFiles.size} file(s) unchanged, " +
                    "returning ${context.cachedFindings.size} cached finding(s)",
            )
            return servedFromCache(context.cachedFindings)
        }
        if (context.cachedFindings.isNotEmpty()) {
            logger.debug(
                "Cache: partial — ${context.unchangedFiles.size} file(s) cached, " +
                    "${context.deltaMr.changes.size} file(s) need re-analysis",
            )
        } else {
            logger.debug("Cache: miss — no prior cache or all files changed")
        }
        return null
    }

    @Suppress("TooGenericExceptionCaught") // boundary: incremental save must not crash pipeline
    fun save(
        findings: List<Finding>,
        logger: Logger,
    ) {
        val current = state.value ?: return
        if (current.context.isFullyCached) return // Don't re-save - prevents finding mutation on repeated hits
        val consumed = registry.ruleModules().firstNotNullOfOrNull { it.tokensConsumed() }
        try {
            current.validator.save(
                current.context,
                findings,
                budgetExhausted = current.context.changedFiles.isNotEmpty() && consumed == 0,
                incompleteRules = aiRule()?.incompleteRules().orEmpty(),
                skipReason = cacheSaveSkipReason(current.context, aiRule()?.health(), consumed),
            )
        } catch (e: Exception) {
            // boundary: optional AI step must not fail the pipeline
            e.rethrowCancellation()
            logger.warn("Failed to save AI incremental state: ${e.message}")
        }
    }

    fun changedFiles(): Set<String>? {
        val ctx = context ?: return null
        return if (ctx.isFullyCached) emptySet() else ctx.changedFiles
    }

    /** The cached findings of a partial hit, to merge with the delta run's; null when fully cached or inactive. */
    fun partialCachedFindings(): List<Finding>? {
        val ctx = context ?: return null
        if (ctx.isFullyCached) return null
        // A rule a policy switched off this run posts nothing, cached or not
        val off = aiRule()?.policyDisabledRules().orEmpty()
        return servedFromCache(ctx.cachedFindings.filterNot { it.rule in off })
    }

    /** Marks findings `(cached)` and tells the AI rule how many it served, for the status line. */
    private fun servedFromCache(findings: List<Finding>): List<Finding> {
        aiRule()?.recordCacheServed(findings.size)
        return findings.map { it.copy(metadata = it.metadata + (WellKnownMetadataKeys.CACHED to "true")) }
    }
}

/**
 * Holds incremental validation state for one pipeline run.
 *
 * Created in [AiIncrementalCache.cachedFindings] and consumed by [AiIncrementalCache.save].
 *
 * Not @Serializable: runtime context holder, holds a non-serializable `IncrementalValidator`.
 */
internal data class IncrementalCacheState(
    val context: IncrementalContext,
    val validator: IncrementalValidator,
)
