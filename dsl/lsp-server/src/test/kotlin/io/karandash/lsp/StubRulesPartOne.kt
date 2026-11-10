package io.karandash.lsp

import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.module.RuleModule

private const val PROP_PATTERN = "pattern"
private const val PROP_MIN_LENGTH = "minLength"
private const val PROP_MAX_LENGTH = "maxLength"

/** Stub rule modules (first half). */
internal object StubRulesPartOne {
    val rules: List<RuleModule> =
        listOf(
            stubRule(
                moduleId = "io.karandash.rule.title-format",
                rules = setOf("titleFormat"),
                desc = "Validates MR/PR title format and length",
                schema = listOf(
                    ConfigProperty(PROP_PATTERN, ConfigPropertyType.STRING, "Regex pattern for title", required = true),
                    ConfigProperty(PROP_MIN_LENGTH, ConfigPropertyType.INT, "Minimum title length"),
                    ConfigProperty(PROP_MAX_LENGTH, ConfigPropertyType.INT, "Maximum title length"),
                    ConfigProperty("requireTicket", ConfigPropertyType.BOOLEAN, "Require ticket reference"),
                )
            ),
            stubRule(
                moduleId = "io.karandash.rule.commit-messages",
                rules = setOf("commitMessages"),
                desc = "Validates commit message format",
                schema = listOf(
                    ConfigProperty("format", ConfigPropertyType.STRING, "Format type"),
                    ConfigProperty(PROP_PATTERN, ConfigPropertyType.STRING, "Regex pattern for messages"),
                    ConfigProperty(PROP_MIN_LENGTH, ConfigPropertyType.INT, "Minimum message length"),
                    ConfigProperty(PROP_MAX_LENGTH, ConfigPropertyType.INT, "Maximum message length"),
                )
            ),
            stubRule(
                moduleId = "io.karandash.rule.commit-body",
                rules = setOf("commitBody"),
                desc = "Validates commit body format",
                schema = listOf(
                    ConfigProperty(PROP_PATTERN, ConfigPropertyType.STRING, "Regex pattern for commit body"),
                    ConfigProperty("required", ConfigPropertyType.BOOLEAN, "Whether commit body is mandatory"),
                    ConfigProperty("matchMode", ConfigPropertyType.STRING, "Match mode: fullMatch, contains, anyLine"),
                )
            ),
            stubRule(
                moduleId = "io.karandash.rule.jira-ticket",
                rules = setOf("jiraTicket"),
                desc = "Validates JIRA ticket references",
                schema = listOf(
                    ConfigProperty(
                        "projectKeys",
                        ConfigPropertyType.STRING_LIST,
                        "Valid JIRA project keys",
                        required = true
                    ),
                    ConfigProperty(PROP_PATTERN, ConfigPropertyType.STRING, "Regex pattern for ticket ID"),
                    ConfigProperty("validateStatus", ConfigPropertyType.STRING_LIST, "Required ticket statuses"),
                    ConfigProperty("validateAssignee", ConfigPropertyType.BOOLEAN, "Validate ticket assignee"),
                )
            ),
            stubRule(
                moduleId = "io.karandash.rule.branch-naming",
                rules = setOf("branchNaming"),
                desc = "Validates branch naming conventions",
                schema = listOf(
                    ConfigProperty(PROP_PATTERN, ConfigPropertyType.STRING, "Regex pattern for branch names"),
                    ConfigProperty("allowedPatterns", ConfigPropertyType.STRING_LIST, "List of allowed patterns"),
                )
            ),
            stubRule(
                "io.karandash.rule.file-changes",
                setOf("fileChanges"),
                "Validates file change restrictions",
                listOf(ConfigProperty("patterns", ConfigPropertyType.OBJECT_LIST, "File pattern rules"))
            ),
            stubRule(
                "io.karandash.rule.codeowners",
                setOf("codeowners"),
                "Validates CODEOWNERS coverage",
                listOf(ConfigProperty("path", ConfigPropertyType.STRING, "Path to CODEOWNERS file"))
            ),
            stubRule(
                "io.karandash.rule.approval",
                setOf("approval"),
                "Validates approval requirements",
                listOf(ConfigProperty("minApprovals", ConfigPropertyType.INT, "Minimum number of approvals"))
            ),
            stubRule(
                "io.karandash.rule.reviewer",
                setOf("reviewer"),
                "Validates reviewer assignments",
                listOf(
                    ConfigProperty("requiredReviewers", ConfigPropertyType.STRING_LIST, "Required reviewer usernames")
                )
            ),
        )
}
