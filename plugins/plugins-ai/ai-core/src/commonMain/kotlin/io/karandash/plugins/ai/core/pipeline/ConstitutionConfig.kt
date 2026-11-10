package io.karandash.plugins.ai.core.pipeline

import kotlinx.serialization.Serializable

/**
 * Configuration for the Constitutional AI reviewer pipeline stage.
 *
 * When [principles] is non-empty, AI-generated findings are evaluated against
 * each principle after generation and before filtering. Findings that violate
 * any principle are removed from the results.
 *
 * Example YAML:
 * ```yaml
 * ai:
 *   constitution:
 *     principles:
 *       - "Never comment on code formatting - linters handle that"
 *       - "Only flag issues with >90% confidence"
 *       - "Always provide actionable suggestions"
 *     model: gpt-4o-mini
 *     self-critique-rounds: 1
 * ```
 *
 * @param principles List of principle strings the AI must not violate.
 * @param model Model used for self-critique calls. Defaults to [ConstitutionalReviewer.DEFAULT_MODEL].
 * @param selfCritiqueRounds Number of review passes. 1 is sufficient for most cases.
 * @param enabled When false the pass is skipped even with [principles] set.
 * @param failureMode [FAILURE_MODE_REMOVE] (default) drops a violating finding;
 *   [FAILURE_MODE_DOWNGRADE] keeps it as INFO with a `[CONSTITUTION]` prefix.
 */
@Serializable
data class ConstitutionConfig(
    val principles: List<String> = emptyList(),
    val model: String? = null,
    val selfCritiqueRounds: Int = 1,
    val enabled: Boolean = true,
    val failureMode: String = FAILURE_MODE_REMOVE,
) {
    companion object {
        const val FAILURE_MODE_REMOVE = "remove"
        const val FAILURE_MODE_DOWNGRADE = "downgrade"
    }
}
