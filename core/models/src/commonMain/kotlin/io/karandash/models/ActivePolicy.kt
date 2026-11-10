package io.karandash.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A policy entry that has been evaluated against a specific MergeRequest.
 * Produced by PolicyEngine; consumed by ValidationEngine and reporters.
 *
 * [stageOverrides] tracks which pipeline stages were gated by this policy
 * (e.g. "autoApprove" -> false means auto-approve is disabled).
 */
@Serializable
data class ActivePolicy(
    val name: String? = null,
    val condition: String,
    val matched: Boolean,
    val affectedModules: List<String>?,
    val forcedEnabled: Boolean?,
    val forcedSeverity: String?,
    val configOverrides: JsonObject?,
    val stageOverrides: Map<String, Boolean> = emptyMap(),
) {
    /** Human-readable label: policy name if available, otherwise the raw condition expression. */
    val displayLabel: String get() = name ?: condition
}
