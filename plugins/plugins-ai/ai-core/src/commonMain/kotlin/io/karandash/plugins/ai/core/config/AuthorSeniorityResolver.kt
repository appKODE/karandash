package io.karandash.plugins.ai.core.config

import io.karandash.api.config.PreserveKeys
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Severity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Author seniority tiers: adjust rule strictness based on developer experience level.
 *
 * Tiers are matched against the MR's labels and author username. The first matching tier
 * is used. If no tier matches, severity is left unchanged.
 */
object AuthorSeniorityResolver {
    /**
     * Returns the first [SeniorityTier] whose [SeniorityTier.matchTeams] contains the MR
     * author's username or any of the MR's labels (case-insensitive), or whose
     * [SeniorityTier.matchAuthors] lists the author (case-insensitive, leading `@` ignored).
     *
     * @param mr The merge request to resolve a tier for.
     * @param tiers Ordered list of seniority tiers to check.
     * @return The matched tier, or null if no tier matches.
     */
    fun resolve(
        mr: MergeRequest,
        tiers: List<SeniorityTier>
    ): SeniorityTier? =
        tiers.firstOrNull { tier ->
            tier.matchTeams.any { team ->
                mr.labels.any { it.equals(team, ignoreCase = true) } ||
                    mr.author.username.equals(team, ignoreCase = true)
            } ||
                tier.matchAuthors.any { normalizeAuthor(it) == normalizeAuthor(mr.author.username) }
        }

    /** Trims, drops a leading `@` and lowercases, so `@Alice` matches the username `alice`. */
    private fun normalizeAuthor(name: String): String = name.trim().removePrefix("@").lowercase()

    /**
     * Adjusts [severity] based on the matched [tier].
     *
     * - An entry for [rule] in [SeniorityTier.adjustments] sets the severity of that rule's findings and wins over the rest.
     * - A non-empty [SeniorityTier.severityAdjustment] maps the severity and replaces the level rules.
     * - [SeniorityLevel.JUNIOR]: ERROR findings are downgraded to WARNING (never block).
     * - [SeniorityLevel.STANDARD] / [SeniorityLevel.SENIOR]: severity is returned unchanged.
     *
     * @param severity The original finding severity.
     * @param tier The resolved seniority tier, or null if no tier matched.
     * @param rule The finding's rule ID, looked up in [SeniorityTier.adjustments].
     * @return The effective severity after applying tier-based adjustment.
     */
    fun adjustSeverity(
        severity: Severity,
        tier: SeniorityTier?,
        rule: String? = null,
    ): Severity {
        if (tier == null) return severity
        rule?.let { tier.adjustments[it] }?.let { return Severity.fromString(it, default = severity) }
        if (tier.severityAdjustment.isNotEmpty()) {
            return tier.severityAdjustment[severity.name.lowercase()]?.let { Severity.fromString(it, default = severity) } ?: severity
        }
        return when (tier.level) {
            SeniorityLevel.JUNIOR -> if (severity == Severity.ERROR) Severity.WARNING else severity
            SeniorityLevel.STANDARD, SeniorityLevel.SENIOR -> severity
        }
    }
}

/** Developer experience level used to calibrate rule strictness. */
@Serializable
enum class SeniorityLevel {
    /** Junior developers receive lenient treatment: errors are downgraded to warnings. */
    @SerialName("junior")
    JUNIOR,

    /** Standard enforcement; no severity adjustments. */
    @SerialName("standard")
    STANDARD,

    /** Senior developers receive full strictness. */
    @SerialName("senior")
    SENIOR,
}

/**
 * A seniority tier configuration.
 *
 * @param level The experience level this tier represents.
 * @param matchTeams Usernames or label names that identify members of this tier.
 * @param adjustments Optional per-rule severity override map (rule ID → severity string).
 * @param matchAuthors Author usernames in this tier (case-insensitive, leading `@` ignored).
 * @param severityAdjustment Severity → severity map (`warning: error`); when set, it replaces the level rules.
 */
@Serializable
data class SeniorityTier(
    val level: SeniorityLevel = SeniorityLevel.STANDARD,
    val matchTeams: List<String> = emptyList(),
    @PreserveKeys val adjustments: Map<String, String> = emptyMap(),
    val matchAuthors: List<String> = emptyList(),
    val severityAdjustment: Map<String, String> = emptyMap(),
)
