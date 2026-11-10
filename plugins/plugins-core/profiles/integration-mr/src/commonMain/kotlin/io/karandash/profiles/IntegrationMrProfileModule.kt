package io.karandash.profiles

import io.karandash.sdk.module.AbstractProfileModule
import io.karandash.validators.CommitBodyRuleModule
import io.karandash.validators.CommitMessagesRuleModule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Opt-in preset (`profile: integration-mr`) for merge requests that carry other people's commits: a `feature/` or
 * `release/` branch merged into `develop`/`main`/`master`, or any MR with more than [MAX_OWN_COMMITS] commits.
 * On those MRs one policy lowers `commitMessages`, `commitBody` and `contextTag` to `warning`; every other MR,
 * and every other rule, keeps the configured severity. Contributes no rule configs, so it only changes severity
 * of rules the config already enables. Policy conditions take literal patterns: to change the branches or the
 * commit threshold, copy [POLICY_CONDITION] into your own `policies:` entry instead of using the profile.
 */
class IntegrationMrProfileModule : AbstractProfileModule() {
    companion object {
        const val PROFILE_ID = "integration-mr"
        const val MAX_OWN_COMMITS = 30

        /** `ContextTagRuleModule.RULE_ID` (plugins-extended); a policy only names it, so no dependency on that tier. */
        private const val CONTEXT_TAG_RULE = "contextTag"

        const val POLICY_CONDITION =
            "(source_branch_matches(\"^(feature|release)/\") && target_branch_matches(\"^(develop|main|master)$\"))" +
                " || commit_count > $MAX_OWN_COMMITS"
    }

    override val name = "Integration MR Profile"
    override val profileId = PROFILE_ID
    override val summary = "Integration merge requests (feature or release into develop/main, or over 30 commits): " +
        "commit message rules report warnings."

    override suspend fun tags() = setOf("commits", "branching")

    override suspend fun ruleConfigs(): Map<String, JsonElement> = emptyMap()

    override suspend fun policies(): List<JsonElement> =
        listOf(
            JsonObject(
                mapOf(
                    "when" to JsonPrimitive(POLICY_CONDITION),
                    "name" to JsonPrimitive(PROFILE_ID),
                    "modules" to JsonArray(
                        listOf(CommitMessagesRuleModule.RULE_ID, CommitBodyRuleModule.RULE_ID, CONTEXT_TAG_RULE).map(::JsonPrimitive),
                    ),
                    "severity" to JsonPrimitive("warning"),
                ),
            ),
        )
}
