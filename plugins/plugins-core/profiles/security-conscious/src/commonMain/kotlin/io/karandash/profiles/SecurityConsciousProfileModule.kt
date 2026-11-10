package io.karandash.profiles

import io.karandash.sdk.config.profileRuleConfigs
import io.karandash.sdk.module.AbstractProfileModule
import io.karandash.validators.FileChangesConfig
import io.karandash.validators.FileChangesRuleModule
import io.karandash.validators.FilePatternRule
import io.karandash.validators.SecurityConfig
import io.karandash.validators.SecurityRuleModule
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.SizeLimitsRuleModule
import io.karandash.validators.TitleFormatConfig
import io.karandash.validators.TitleFormatRuleModule

class SecurityConsciousProfileModule : AbstractProfileModule() {
    companion object {
        const val PROFILE_ID = "security-conscious"
        const val MIN_TITLE_LENGTH = 10
        const val MAX_LINES = 400
        const val MAX_COMMITS = 15

        /** Label that, together with a non-author approval, unblocks an MR that touches [BLOCKED_FILE_PATTERNS]. */
        const val SECURITY_REVIEW_LABEL = "security-reviewed"
        val BLOCKED_FILE_PATTERNS = listOf(
            "**/.env",
            "**/*.pem",
            "**/*.key",
            "**/*.p12",
            "**/secrets/**",
            "**/.aws/credentials",
        )
    }

    override val name = "Security-Conscious Profile"
    override val profileId = PROFILE_ID
    override val summary = "Security-first defaults — enables security scanning, blocks sensitive file changes."

    override suspend fun tags() = setOf("security", "size", "file-changes")

    override suspend fun ruleConfigs() =
        profileRuleConfigs {
            rule(SecurityRuleModule.RULE_ID, SecurityConfig(scanForSecrets = true))
            rule(
                FileChangesRuleModule.RULE_ID,
                FileChangesConfig(
                    patterns = BLOCKED_FILE_PATTERNS.map {
                        FilePatternRule(
                            pattern = it,
                            requireLabels = listOf(SECURITY_REVIEW_LABEL),
                            requireNonAuthorApproval = true,
                        )
                    },
                ),
            )
            rule(
                SizeLimitsRuleModule.RULE_ID,
                SizeLimitsConfig(maxLines = MAX_LINES, maxCommits = MAX_COMMITS),
            )
            rule(
                TitleFormatRuleModule.RULE_ID,
                TitleFormatConfig(minLength = MIN_TITLE_LENGTH),
            )
        }
}
