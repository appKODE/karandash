package io.karandash.api.context

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding

/**
 * Context for evaluating condition predicates.
 *
 * Contains the merge request data plus environment, CI, config state,
 * and time context for policy evaluation.
 *
 * @param mr The merge request to evaluate conditions against.
 * @param priorFindings Findings from rules that have already been evaluated.
 * @param env Allowlisted environment variable lookup function.
 * @param ciPlatform Detected CI platform name (e.g., "gitlab", "github").
 * @param isCI Whether running in a CI environment.
 * @param profileId Active profile ID, if any.
 * @param dryRun Whether dry-run mode is active.
 * @param configuredModuleIds Set of all module IDs present in the config.
 * @param nowEpochSeconds Current time as epoch seconds for age-based predicates.
 *
 * Not @Serializable: runtime context holder, carries the env lookup lambda.
 */
public data class ConditionContext(
    val mr: MergeRequest,
    val priorFindings: List<Finding> = emptyList(),
    val env: (String) -> String? = { null },
    val ciPlatform: String? = null,
    val isCI: Boolean = false,
    val profileId: String? = null,
    val dryRun: Boolean = false,
    val configuredModuleIds: Set<String> = emptySet(),
    val nowEpochSeconds: Long = 0L,
)
