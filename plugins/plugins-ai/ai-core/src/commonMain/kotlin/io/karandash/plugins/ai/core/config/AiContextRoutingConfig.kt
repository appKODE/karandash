package io.karandash.plugins.ai.core.config

import io.karandash.plugins.ai.core.router.ModelTier
import io.karandash.plugins.ai.core.router.RoutingRule
import io.karandash.plugins.ai.core.router.RoutingStrategy
import kotlinx.serialization.Serializable

/**
 * `ai.chunking`: split a diff larger than [chunkSize] characters into at most [maxChunks] chunks,
 * review each independently and merge the findings with deduplication. A single file larger than
 * [chunkSize] is split on hunk boundaries; consecutive windows share up to [overlapSize] characters
 * of hunks. `enabled: false` reviews the diff in one pass (truncated to the diff limits instead).
 * Without the key, chunking runs on the diff cap (`ai.budget`, or `ai.limits.maxDiffCharsTotal`) and `ai.limits.maxChunks`,
 * splitting a large file on hunks without overlap. Files past the chunk limit are recorded as dropped.
 */
@Serializable
data class ChunkingConfig(
    val enabled: Boolean = true,
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    val overlapSize: Int = DEFAULT_OVERLAP_SIZE,
    val maxChunks: Int = DEFAULT_MAX_CHUNKS,
) {
    /** Clamps the sizes to usable values: chunk size and chunk count at least 1, overlap below the chunk size. */
    fun normalized(): ChunkingConfig {
        val size = chunkSize.coerceAtLeast(1)
        return copy(chunkSize = size, overlapSize = overlapSize.coerceIn(0, size - 1), maxChunks = maxChunks.coerceAtLeast(1))
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 10_000
        const val DEFAULT_OVERLAP_SIZE = 2_000
        const val DEFAULT_MAX_CHUNKS = 10
    }
}

/**
 * `ai.incremental`: on a new push review only the files whose diff changed since the last reviewed
 * run (needs `cache.state`), reusing the cached findings of the rest. On by default when a state store
 * is configured; `enabled: false` reviews the whole diff on every run.
 */
@Serializable
data class IncrementalConfig(
    val enabled: Boolean = true,
)

/**
 * `ai.crossFileContext`: add the content of files related to the changed ones (resolved imports,
 * matching tests) to the prompt under "Context from Dependencies", within `ai.crossFileContextMaxChars`.
 *
 * @property includeImports Resolve the changed files' imports to repository files.
 * @property includeTests Add the test file that matches a changed source file.
 * @property maxContextFiles At most this many related files.
 * @property captureSignatures Send only declarations (signatures) of each file instead of its full text.
 */
@Serializable
data class CrossFileContextConfig(
    val enabled: Boolean = true,
    val includeImports: Boolean = true,
    val includeTests: Boolean = true,
    val maxContextFiles: Int = DEFAULT_MAX_CONTEXT_FILES,
    val captureSignatures: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_CONTEXT_FILES = 5
    }
}

/**
 * `ai.processRisk`: score the MR's process risk (0.0-1.0) from the enabled [factors], log the
 * breakdown and add it as an INFO finding.
 */
@Serializable
data class ProcessRiskConfig(
    val enabled: Boolean = true,
    val factors: ProcessRiskFactors = ProcessRiskFactors(),
)

/**
 * Which process-risk factors count toward the composite score; disabled ones are left out and the
 * remaining weights are rescaled.
 *
 * @property changeChurn Gross lines changed relative to the net change.
 * @property fileDiversity How many directories the MR touches.
 * @property changeDensity Few commits spread over many files.
 * @property authorFamiliarity Files the author has not changed before (neutral 0.5 without history).
 */
@Serializable
data class ProcessRiskFactors(
    val changeChurn: Boolean = true,
    val fileDiversity: Boolean = true,
    val changeDensity: Boolean = true,
    val authorFamiliarity: Boolean = true,
)

/**
 * `ai.routing`: cheap-model triage. Rules run on the last (cheapest) tier's first model unless the MR's
 * process risk reaches [riskThreshold]; then they run on the tier their [rules] entry names (default:
 * the first, strongest tier). `strategy: quality-first` always uses the rule's tier. An explicit
 * `ai.models.<rule>` entry wins over routing.
 */
@Serializable
data class RoutingConfig(
    val enabled: Boolean = true,
    val strategy: RoutingStrategy = RoutingStrategy.COST_OPTIMIZED,
    val tiers: List<ModelTier> = emptyList(),
    val rules: List<RoutingRule> = emptyList(),
    val riskThreshold: Double = DEFAULT_RISK_THRESHOLD,
) {
    companion object {
        const val DEFAULT_RISK_THRESHOLD = 0.5
    }
}
