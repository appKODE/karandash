package io.karandash.plugins.ai.core.config

import io.karandash.api.config.PreserveKeys
import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.ReviewState
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Environment track switching: per-target-branch rule configuration.
 *
 * Different target branches can have different strictness levels.
 * Production branches get strict rules; feature branches get relaxed rules.
 */
object EnvironmentTrackResolver {
    /**
     * Finds the matching track for the given target branch.
     *
     * The first track whose [TrackConfig.targetBranchPattern] matches anywhere in
     * [targetBranch] is returned (first-match wins). Pattern is treated as a regex.
     * Malformed patterns are silently skipped.
     *
     * @param targetBranch The MR's target branch name.
     * @param tracks List of configured tracks, evaluated in order.
     * @return The first matching track config, or null if no track matches.
     */
    fun resolve(
        targetBranch: String,
        tracks: List<TrackConfig>
    ): TrackConfig? =
        tracks.firstOrNull { track ->
            RegexHelper.compileOrNull(track.targetBranchPattern)?.containsMatchIn(targetBranch) == true
        }

    /**
     * an error when [mr] has fewer approvals than the track's [TrackConfig.minApprovals], or null when it
     * has enough or the track sets none.
     */
    fun approvalsFinding(
        track: TrackConfig,
        mr: MergeRequest,
    ): Finding? {
        val required = track.minApprovals ?: return null
        val approved = mr.approvals.count { it.state == ReviewState.APPROVED }
        if (approved >= required) return null
        return Finding(
            severity = Severity.ERROR,
            message = "Environment track '${track.name}' requires $required approval(s) for ${mr.targetBranch}, the MR has $approved",
            rule = APPROVALS_RULE_ID,
        )
    }

    /** Rule id of [approvalsFinding]. */
    const val APPROVALS_RULE_ID = "ai-track-approvals"

    /**
     * Applies track overrides to determine the effective set of enabled rule IDs.
     *
     * [TrackConfig.enableRules] acts as an inclusion filter (substring match against rule IDs).
     * When empty, all rules are included. [TrackConfig.disableRules] then removes matches.
     *
     * @param track The matched track configuration.
     * @param allRuleIds All available rule IDs.
     * @return Set of rule IDs enabled under this track.
     */
    fun filterRules(
        track: TrackConfig,
        allRuleIds: Set<String>
    ): Set<String> {
        val included = if (track.enableRules.isNotEmpty()) {
            allRuleIds.filter { ruleId -> track.enableRules.any { ruleId.contains(it) } }.toSet()
        } else {
            allRuleIds
        }
        return if (track.disableRules.isNotEmpty()) {
            included.filter { ruleId -> track.disableRules.none { ruleId.contains(it) } }.toSet()
        } else {
            included
        }
    }
}

/** Configuration for a single environment track matched by target branch pattern. */
@Serializable
data class TrackConfig(
    /** Human-readable name for this track (e.g. "production", "feature"). */
    val name: String = "",
    /** Regex pattern matched against the MR's target branch name. */
    val targetBranchPattern: String,
    /** Substring fragments: only rules containing one of these are included. Empty = all rules. */
    val enableRules: List<String> = emptyList(),
    /** Substring fragments: rules containing any of these are excluded after inclusion filtering. */
    val disableRules: List<String> = emptyList(),
    /** Whether AI-powered rules are active on this track. */
    val aiEnabled: Boolean = true,
    /** Approvals the MR needs on this track; fewer raise an [EnvironmentTrackResolver.APPROVALS_RULE_ID] error. Null: no check. */
    val minApprovals: Int? = null,
    /** Per-rule settings (`severity`/`enforceSeverity`, `enabled`) applied to findings on this track. */
    @PreserveKeys val ruleOverrides: Map<String, JsonElement> = emptyMap(),
)
