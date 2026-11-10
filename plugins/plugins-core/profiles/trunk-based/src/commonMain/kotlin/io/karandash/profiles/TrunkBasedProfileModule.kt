package io.karandash.profiles

import io.karandash.sdk.config.BlockMode
import io.karandash.sdk.config.profileRuleConfigs
import io.karandash.sdk.module.AbstractProfileModule
import io.karandash.validators.BranchNamingConfig
import io.karandash.validators.BranchNamingRuleModule
import io.karandash.validators.DraftConfig
import io.karandash.validators.DraftRuleModule
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.SizeLimitsRuleModule
import io.karandash.validators.TitleFormatConfig
import io.karandash.validators.TitleFormatRuleModule

class TrunkBasedProfileModule : AbstractProfileModule() {
    companion object {
        const val PROFILE_ID = "trunk-based"
        const val MIN_TITLE_LENGTH = 5
        const val MAX_SUBJECT_LENGTH = 72
        const val MAX_LINES = 200
        const val MAX_COMMITS = 5
    }

    override val name = "Trunk-Based Profile"
    override val profileId = PROFILE_ID
    override val summary = "Trunk-based development — small, fast PRs with strict size and draft enforcement."

    override suspend fun tags() = setOf("size", "branching", "draft-prevention")

    override suspend fun ruleConfigs() =
        profileRuleConfigs {
            rule(
                TitleFormatRuleModule.RULE_ID,
                TitleFormatConfig(minLength = MIN_TITLE_LENGTH, maxLength = MAX_SUBJECT_LENGTH),
            )
            rule(
                SizeLimitsRuleModule.RULE_ID,
                SizeLimitsConfig(maxLines = MAX_LINES, maxCommits = MAX_COMMITS),
            )
            rule(
                DraftRuleModule.RULE_ID,
                DraftConfig(
                    blockDraft = BlockMode.FAIL,
                    wipPatterns = listOf("WIP", "\\[WIP\\]", "wip:", "WIP:", "DO NOT MERGE"),
                ),
            )
            rule(
                BranchNamingRuleModule.RULE_ID,
                BranchNamingConfig(pattern = "^(main|master|trunk|[a-z0-9][a-z0-9._/-]{0,49})$"),
            )
        }
}
