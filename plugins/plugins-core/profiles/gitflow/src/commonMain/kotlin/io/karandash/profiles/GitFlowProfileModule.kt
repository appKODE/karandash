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

class GitFlowProfileModule : AbstractProfileModule() {
    companion object {
        const val PROFILE_ID = "gitflow"
        const val MIN_TITLE_LENGTH = 10
        const val MAX_TITLE_LENGTH = 100
        const val MAX_LINES = 600
        const val MAX_COMMITS = 30
    }

    override val name = "GitFlow Profile"
    override val profileId = PROFILE_ID
    override val summary = "Traditional GitFlow — feature, release, hotfix, and develop branch conventions."

    override suspend fun tags() = setOf("branching", "title")

    override suspend fun ruleConfigs() =
        profileRuleConfigs {
            rule(
                TitleFormatRuleModule.RULE_ID,
                TitleFormatConfig(minLength = MIN_TITLE_LENGTH, maxLength = MAX_TITLE_LENGTH),
            )
            rule(
                BranchNamingRuleModule.RULE_ID,
                BranchNamingConfig(
                    pattern = "^(main|master|develop|feature/.+|release/.+|hotfix/.+|support/.+)$",
                    requireTargetMatch = true,
                ),
            )
            rule(
                SizeLimitsRuleModule.RULE_ID,
                SizeLimitsConfig(maxLines = MAX_LINES, maxCommits = MAX_COMMITS),
            )
            rule(DraftRuleModule.RULE_ID, DraftConfig(blockDraft = BlockMode.FAIL))
        }
}
