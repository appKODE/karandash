package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.utils.CollectionMatcher
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.validation.toContext

class ReviewerValidator(
    private val config: ReviewerConfig
) {
    private companion object {
        const val TEAMS_EXPLANATION =
            "requiredTeams passes when a reviewer username matches any of the listed names; " +
                "this is a username match, not team membership, and the platform is not asked for teams."
    }

    fun validate(mr: MergeRequest): List<Finding> {
        val findings = mutableListOf<Finding>()

        val reviewerUsernames = mr.toContext().reviewerNames

        if (config.requiredReviewers.isNotEmpty()) {
            val missingReviewers = CollectionMatcher.findMissing(reviewerUsernames, config.requiredReviewers)

            if (missingReviewers.isNotEmpty()) {
                findings.add(
                    FindingFactory.error(
                        rule = ReviewerRuleModule.RULE_ID,
                        message = "Missing required reviewers: ${missingReviewers.joinToString(", ")}",
                        suggestion = ReviewerText.SUGGESTION.en,
                        explanation = ReviewerText.EXPLANATION.en
                    )
                )
            }
        }

        if (config.requiredTeams.isNotEmpty()) {
            val hasTeamMember = config.requiredTeams.any { team ->
                reviewerUsernames.any { reviewer ->
                    matchesTeam(reviewer, team)
                }
            }

            if (!hasTeamMember) {
                findings.add(
                    FindingFactory.error(
                        rule = ReviewerRuleModule.RULE_ID,
                        message = "Missing reviewer from required teams: ${config.requiredTeams.joinToString(", ")}",
                        suggestion = ReviewerText.SUGGESTION.en,
                        explanation = TEAMS_EXPLANATION
                    )
                )
            }
        }

        // Check for excluded reviewers
        if (config.excludedReviewers.isNotEmpty()) {
            val forbiddenReviewers = CollectionMatcher.findPresent(reviewerUsernames, config.excludedReviewers)

            if (forbiddenReviewers.isNotEmpty()) {
                findings.add(
                    FindingFactory.error(
                        rule = ReviewerRuleModule.RULE_ID,
                        message = "Excluded reviewers present: ${forbiddenReviewers.joinToString(", ")}",
                        suggestion = ReviewerText.SUGGESTION.en,
                        explanation = ReviewerText.EXPLANATION.en
                    )
                )
            }
        }

        return findings
    }

    private fun matchesTeam(
        reviewer: String,
        team: String
    ): Boolean =
        when (config.teamMatchMode) {
            ReviewerConfig.TEAM_MATCH_CONTAINS -> reviewer.contains(team, ignoreCase = true)
            else -> reviewer.equals(team, ignoreCase = true)
        }
}
