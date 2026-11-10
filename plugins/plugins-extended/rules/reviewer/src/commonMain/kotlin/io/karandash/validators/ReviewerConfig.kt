package io.karandash.validators

import io.karandash.api.RuleConfig
import kotlinx.serialization.Serializable

@Serializable
data class ReviewerConfig(
    val requiredReviewers: List<String> = emptyList(),
    /** Passes when any reviewer username matches any of these names; not a team membership lookup. */
    val requiredTeams: List<String> = emptyList(),
    val excludedReviewers: List<String> = emptyList(),
    val teamMatchMode: String = TEAM_MATCH_EXACT,
) : RuleConfig {
    companion object {
        const val TEAM_MATCH_EXACT = "exact"
        const val TEAM_MATCH_CONTAINS = "contains"
    }
}
