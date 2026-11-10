package io.karandash.profiles

import io.karandash.sdk.config.profileRuleConfigs
import io.karandash.sdk.module.AbstractProfileModule
import io.karandash.validators.CommitBodyConfig
import io.karandash.validators.CommitBodyRuleModule
import io.karandash.validators.CommitMessagesConfig
import io.karandash.validators.CommitMessagesRuleModule
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.SizeLimitsRuleModule
import io.karandash.validators.TitleFormatConfig
import io.karandash.validators.TitleFormatRuleModule

class SemanticVersioningProfileModule : AbstractProfileModule() {
    override val name = "Semantic Versioning Profile"
    override val profileId = PROFILE_ID
    override val summary = "Semantic versioning — feat is a MINOR bump, fix and perf a PATCH bump, and a breaking `!` commit " +
        "(MAJOR bump) must carry a BREAKING CHANGE body line."

    override suspend fun tags() = setOf("commits", "title", "semantic-versioning")

    override suspend fun ruleConfigs() =
        profileRuleConfigs {
            rule(
                TitleFormatRuleModule.RULE_ID,
                TitleFormatConfig(
                    pattern = SEMVER_PATTERN,
                    minLength = MIN_TITLE_LENGTH,
                    maxLength = MAX_SUBJECT_LENGTH,
                ),
            )
            rule(
                CommitMessagesRuleModule.RULE_ID,
                CommitMessagesConfig(
                    pattern = SEMVER_PATTERN,
                    maxLength = MAX_SUBJECT_LENGTH,
                ),
            )
            rule(
                CommitBodyRuleModule.RULE_ID,
                CommitBodyConfig(
                    pattern = BREAKING_BODY_PATTERN,
                    requiredWhenSubjectMatches = BREAKING_SUBJECT_PATTERN,
                    matchMode = "anyLine",
                ),
            )
            rule(
                SizeLimitsRuleModule.RULE_ID,
                SizeLimitsConfig(maxLines = MAX_LINES, maxCommits = MAX_COMMITS),
            )
        }

    companion object {
        const val PROFILE_ID = "semantic-versioning"
        const val SEMVER_PATTERN =
            "^(feat|fix|perf|refactor|style|test|docs|build|ci|chore|revert)(\\(.+\\))?!?:.*"

        /** A `!` before the colon marks a breaking change (MAJOR bump). */
        const val BREAKING_SUBJECT_PATTERN = "^[a-z]+(\\(.+\\))?!:"

        /** A breaking `!` commit must also state it in the body, as the `BREAKING CHANGE:` footer. */
        const val BREAKING_BODY_PATTERN = "^BREAKING[ -]CHANGE:\\s*\\S"
        const val MIN_TITLE_LENGTH = 10
        const val MAX_SUBJECT_LENGTH = 72
        const val MAX_LINES = 800
        const val MAX_COMMITS = 30
    }
}
