package io.karandash.plugins.ai.core.config

import kotlinx.serialization.Serializable

/**
 * Configuration for the "prior findings as prompt context" feature.
 *
 * When enabled, findings persisted by previous runs for files in the current delta
 * (and previous MR-level findings) are attached to the AI prompt as read-only context.
 * The prompt marks them as already raised and tells the model not to repeat them; only new
 * issues are reported.
 *
 * Dismissed findings (tracked via FeedbackStore) are filtered out before the enricher
 * renders them, so the model is never nudged to re-surface something the reviewer
 * explicitly rejected.
 *
 * ```yaml
 * ai:
 *   pipeline:
 *     prior-findings:
 *       enabled: true
 *       max-per-file: 20
 *       max-for-mr-level: 10
 * ```
 */
@Serializable
data class AiPriorFindingsConfig(
    val enabled: Boolean = true,
    val maxPerFile: Int = DEFAULT_UNLIMITED,
    val maxForMrLevel: Int = DEFAULT_UNLIMITED,
) {
    init {
        require(maxPerFile > 0) { "max-per-file must be positive (got $maxPerFile)" }
        require(maxForMrLevel > 0) { "max-for-mr-level must be positive (got $maxForMrLevel)" }
    }

    companion object {
        const val DEFAULT_UNLIMITED: Int = Int.MAX_VALUE
    }
}
