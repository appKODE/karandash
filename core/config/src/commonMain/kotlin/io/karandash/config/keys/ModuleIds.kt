package io.karandash.config.keys

/**
 * Cross-module string constants for module IDs that are referenced
 * by code outside the module's own package.
 *
 * Module IDs are normally defined as companion-object constants within
 * each module class. When a different layer (e.g. CLI pipeline) needs
 * to reference a module ID but cannot depend on the module's Gradle
 * project, the ID belongs here instead.
 */
object ModuleIds {
    /** AI analysis rule module (`AiRuleModule.id`). */
    const val AI_ANALYSIS = "ai-analysis"

    /** Draft prevention rule (`DraftRuleModule.RULE_ID`). */
    const val DRAFT_PREVENTION = "draftPrevention"

    /** Approval rule (`ApprovalRuleModule.RULE_ID`, from helpers `RuleIds.APPROVAL`); `simulate` evaluates it outside the pipeline. */
    const val APPROVAL = "approval"

    /** Minimal profile (`MinimalProfileModule.PROFILE_ID`); the setup wizard's default choice. */
    const val MINIMAL_PROFILE = "minimal"

    /** Local git platform service (`LocalGitProvider.ID`); `validate-local` selects it. */
    const val LOCAL_GIT = "local-git"

    /** Markdown format (`MarkdownFormatModule.FORMAT_ID`); a dry run clears its [FLOW_ARTIFACT_DIR_KEY]. */
    const val MARKDOWN_FORMAT = "markdown"

    /** Markdown format config key `flowArtifactDir` (`MarkdownFormatConfig.flowArtifactDir`). */
    const val FLOW_ARTIFACT_DIR_KEY = "flowArtifactDir"

    /** Documented short rule keys under `rules:` mapped to the real rule ID. The real key wins when both are set. */
    val RULE_ALIASES: Map<String, String> = mapOf("draft" to DRAFT_PREVENTION)

    /** Documented short `reporters:` keys mapped to the real format key (`reviewdog` writes the `rdjson` format). */
    val REPORTER_ALIASES: Map<String, String> = mapOf("reviewdog" to "rdjson")
}
