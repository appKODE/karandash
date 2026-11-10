package io.karandash.profiles

import io.karandash.sdk.config.BlockMode
import io.karandash.sdk.config.profileRuleConfigs
import io.karandash.sdk.module.AbstractProfileModule
import io.karandash.validators.DraftConfig
import io.karandash.validators.DraftRuleModule
import io.karandash.validators.FileChangesConfig
import io.karandash.validators.FileChangesRuleModule
import io.karandash.validators.LabelsConfig
import io.karandash.validators.LabelsRuleModule
import io.karandash.validators.SecurityConfig
import io.karandash.validators.SecurityRuleModule
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.SizeLimitsRuleModule
import io.karandash.validators.TitleFormatConfig
import io.karandash.validators.TitleFormatRuleModule

class MinimalProfileModule : AbstractProfileModule() {
    companion object {
        const val PROFILE_ID = "minimal"
        const val MIN_TITLE_LENGTH = 10
        const val MAX_LINES = 500
        const val MAX_COMMITS = 20

        /** zero-config findings are advisory, so a fresh install never hard-fails an MR. */
        private const val SEVERITY = "warning"
    }

    override val name = "Minimal Profile"
    override val profileId = PROFILE_ID
    override val summary = "Sensible non-blocking defaults — title format, draft detection, and size limits."

    override suspend fun tags() = setOf("title", "draft-prevention", "size")

    override val isDefaultZeroConfigProfile = true

    override suspend fun ruleConfigs() =
        profileRuleConfigs {
            rule(
                TitleFormatRuleModule.RULE_ID,
                TitleFormatConfig(
                    pattern = "^(feat|fix|perf|refactor|style|test|docs|build|ci|chore|revert)(\\(.+\\))?!?:.*",
                    minLength = MIN_TITLE_LENGTH,
                ),
                SEVERITY,
            )
            rule(
                DraftRuleModule.RULE_ID,
                DraftConfig(
                    blockDraft = BlockMode.OFF,
                    wipPatterns = listOf("WIP", "\\[WIP\\]", "wip:", "WIP:"),
                ),
                SEVERITY,
            )
            rule(
                SizeLimitsRuleModule.RULE_ID,
                SizeLimitsConfig(maxLines = MAX_LINES, maxCommits = MAX_COMMITS),
                SEVERITY,
            )
            rule(FileChangesRuleModule.RULE_ID, FileChangesConfig(), SEVERITY)
            rule(SecurityRuleModule.RULE_ID, SecurityConfig(), SEVERITY)
            rule(LabelsRuleModule.RULE_ID, LabelsConfig(), SEVERITY)
        }
}
