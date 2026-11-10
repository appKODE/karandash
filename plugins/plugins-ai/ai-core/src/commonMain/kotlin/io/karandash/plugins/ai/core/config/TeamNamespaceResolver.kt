package io.karandash.plugins.ai.core.config

import io.karandash.api.config.PreserveKeys
import io.karandash.models.FileChange
import io.karandash.utils.GlobMatcher
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Team namespace scoping for monorepos.
 * Different teams get different rules based on file ownership patterns.
 */
object TeamNamespaceResolver {
    /**
     * Determines which teams own the changed files.
     *
     * @param changes The list of file changes in the MR.
     * @param teams The list of team configurations with ownership patterns.
     * @return Map of team name to the files they own in this MR.
     */
    fun resolveOwnership(
        changes: List<FileChange>,
        teams: List<TeamConfig>
    ): Map<String, List<FileChange>> {
        val result = mutableMapOf<String, MutableList<FileChange>>()
        for (change in changes) {
            for (team in teams) {
                if (team.owns.any { pattern -> GlobMatcher.matches(change.path, pattern) }) {
                    result.getOrPut(team.name) { mutableListOf() }.add(change)
                }
            }
        }
        return result
    }

    /**
     * Returns the set of team names that own any of the changed files.
     *
     * @param changes The list of file changes in the MR.
     * @param teams The list of team configurations with ownership patterns.
     * @return Set of team names that own at least one changed file.
     */
    fun affectedTeams(
        changes: List<FileChange>,
        teams: List<TeamConfig>
    ): Set<String> = resolveOwnership(changes, teams).keys
}

/** Configuration for a single team's file ownership and rule overrides. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TeamConfig(
    /** The team's unique name. */
    val name: String,
    /** Glob patterns for files owned by this team. */
    val owns: List<String> = emptyList(),
    /** Rule configuration overrides applied when this team's files are changed; `rules` is the same key. */
    @JsonNames("rules")
    @PreserveKeys val ruleOverrides: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
)
