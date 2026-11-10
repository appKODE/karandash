package io.karandash.lsp

import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.module.RuleModule

internal object StubRulesPartTwo {
    val rules: List<RuleModule> =
        listOf(
            stubRule(
                "io.karandash.rule.cicd-status",
                setOf("cicdStatus"),
                "Validates CI/CD pipeline status",
                listOf(ConfigProperty("requirePassed", ConfigPropertyType.BOOLEAN, "Require CI/CD to pass"))
            ),
            stubRule(
                "io.karandash.rule.conflict",
                setOf("conflict"),
                "Detects merge conflicts",
                listOf(ConfigProperty("blockOnConflicts", ConfigPropertyType.BOOLEAN, "Block on merge conflicts"))
            ),
            stubRule(
                "io.karandash.rule.draft",
                setOf("draftPrevention"),
                "Detects draft/WIP merge requests",
                listOf(ConfigProperty("blockDraft", ConfigPropertyType.BOOLEAN, "Block draft MRs/PRs"))
            ),
            stubRule(
                "io.karandash.rule.labels",
                setOf("labels"),
                "Validates label requirements",
                listOf(ConfigProperty("requiredLabels", ConfigPropertyType.STRING_LIST, "Required label names"))
            ),
            stubRule(
                "io.karandash.rule.size-limits",
                setOf("sizeLimits"),
                "Validates MR size limits",
                listOf(ConfigProperty("maxFiles", ConfigPropertyType.INT, "Maximum number of files"))
            ),
            stubRule(
                "io.karandash.rule.security",
                setOf("security"),
                "Detects security patterns",
                listOf(ConfigProperty("scanForSecrets", ConfigPropertyType.BOOLEAN, "Enable secret scanning"))
            ),
            stubRule(
                "io.karandash.rule.context-tag",
                setOf("contextTag"),
                "Validates context tags",
                listOf(ConfigProperty("allowed", ConfigPropertyType.STRING_LIST, "Allowed context tags"))
            ),
            stubRule(
                "io.karandash.rule.description-format",
                setOf("descriptionFormat"),
                "Validates MR description format",
                listOf(ConfigProperty("requiredSections", ConfigPropertyType.STRING_LIST, "Required section names"))
            ),
            stubRule(
                "io.karandash.rule.cross-entity-consistency",
                setOf("crossEntityConsistency"),
                "Cross-entity consistency checks",
                listOf(ConfigProperty("taskIdPattern", ConfigPropertyType.STRING, "Regex pattern for task IDs"))
            ),
            stubRule(
                "io.karandash.rule.language-policy",
                setOf("languagePolicy"),
                "Enforces language policies",
                listOf(ConfigProperty("allowedScripts", ConfigPropertyType.STRING_LIST, "Allowed scripts"))
            ),
            stubRule(
                "io.karandash.rule.ai",
                setOf("ai"),
                "AI-powered rules",
                listOf(ConfigProperty("provider", ConfigPropertyType.STRING, "AI provider name"))
            ),
            stubRule(
                "io.karandash.rule.jira",
                setOf("jira"),
                "JIRA ticket validation and enrichment",
                listOf(ConfigProperty("baseUrl", ConfigPropertyType.STRING, "Jira base URL"))
            ),
            stubRule(
                "io.karandash.rule.dependencies",
                setOf("dependencies"),
                "Dependency validation",
                listOf(ConfigProperty("lockfileRequired", ConfigPropertyType.BOOLEAN, "Require lockfile"))
            ),
        )
}
