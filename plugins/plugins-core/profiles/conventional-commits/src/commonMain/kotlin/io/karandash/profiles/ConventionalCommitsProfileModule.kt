package io.karandash.profiles

import io.karandash.sdk.config.BlockMode
import io.karandash.sdk.config.profileRuleConfigs
import io.karandash.sdk.module.AbstractProfileModule
import io.karandash.validators.CommitMessagesConfig
import io.karandash.validators.CommitMessagesRuleModule
import io.karandash.validators.DraftConfig
import io.karandash.validators.DraftRuleModule
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.SizeLimitsRuleModule
import io.karandash.validators.TitleFormatConfig
import io.karandash.validators.TitleFormatRuleModule

class ConventionalCommitsProfileModule : AbstractProfileModule() {
    override val name = "Conventional Commits Profile"
    override val profileId = PROFILE_ID
    override val summary = "Enforces the Conventional Commits specification for titles and commit messages."

    override suspend fun tags() = setOf("title", "commits", "semantic-versioning")

    override suspend fun ruleConfigs() =
        profileRuleConfigs {
            rule(
                TitleFormatRuleModule.RULE_ID,
                TitleFormatConfig(
                    pattern = CC_PATTERN,
                    minLength = MIN_TITLE_LENGTH,
                    maxLength = MAX_SUBJECT_LENGTH,
                ),
            )
            rule(
                CommitMessagesRuleModule.RULE_ID,
                CommitMessagesConfig(
                    pattern = CC_PATTERN,
                    maxLength = MAX_SUBJECT_LENGTH,
                ),
            )
            rule(
                SizeLimitsRuleModule.RULE_ID,
                SizeLimitsConfig(maxLines = MAX_LINES, maxCommits = MAX_COMMITS),
            )
            rule(DraftRuleModule.RULE_ID, DraftConfig(blockDraft = BlockMode.FAIL))
        }

    companion object {
        const val PROFILE_ID = "conventional-commits"
        const val CC_PATTERN =
            "^(feat|fix|chore|docs|test|refactor|style|perf|build|ci|revert)(\\(.+\\))?!?:.*"
        const val MIN_TITLE_LENGTH = 10
        const val MAX_SUBJECT_LENGTH = 72
        const val MAX_LINES = 800
        const val MAX_COMMITS = 25
    }
}
