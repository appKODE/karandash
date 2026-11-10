package io.karandash.plugins.ai.core.rules

/**
 * Deep-merges a global [AiContextQualityConfig] with a per-rule
 * [PerRuleContextConfig] override. Null fields on the override inherit from
 * the global default; non-null fields replace the global field wholesale.
 *
 * This is a whole-object override at the sub-config level - for example
 * `ruleContext["review-comments"].relatedFiles = RelatedFilesConfig(enabled=false)`
 * replaces the global [RelatedFilesConfig] entirely, even if the user only
 * meant to flip `enabled` (the other fields revert to their constructor
 * defaults). A "deep merge" of nested scalar fields is deferred
 * until per-rule configs are actually deserialised by the config pipeline -
 * at that point the parser can produce a sparse [PerRuleContextConfig] and
 * this resolver will compose it with the global defaults.
 *
 * For now, [resolveForRule] is pure and deterministic. A rule ID of null
 * always returns the global config unchanged.
 */
object PerRuleContextResolver {
    private val NON_ID_CHAR_REGEX = Regex("[^a-z0-9-]")
    private val HYPHEN_RUN_REGEX = Regex("-+")

    /**
     * @param ruleId The rule being evaluated. Null → returns [global] unchanged.
     * @param global The top-level `ai.contextQuality` configuration.
     * @return An [AiContextQualityConfig] reflecting the merged view for [ruleId].
     */
    fun resolveForRule(
        ruleId: String?,
        global: AiContextQualityConfig,
    ): AiContextQualityConfig {
        if (ruleId == null) return global
        val override = global.ruleContext[ruleId.normaliseRuleId()] ?: return global
        return merge(global, override)
    }

    /** Apply the per-rule overrides on top of [global]. */
    internal fun merge(
        global: AiContextQualityConfig,
        override: PerRuleContextConfig,
    ): AiContextQualityConfig =
        global.copy(
            truncation = override.truncation ?: global.truncation,
            relatedFiles = override.relatedFiles ?: global.relatedFiles,
            fullFileContext = override.fullFileContext ?: global.fullFileContext,
            conventions = override.conventions ?: global.conventions,
            findingValidator = override.findingValidator ?: global.findingValidator,
            knowledgeContext = override.knowledgeContext?.let {
                KnowledgeContextConfig(it.defaultBudget, global.knowledgeContext.budgets + it.budgets)
            } ?: global.knowledgeContext,
        )

    /**
     * Normalise a rule ID to match the [ruleContext] key format - lowercase,
     * hyphen-separated. Matches the project's existing `normalizeRuleId`
     * behaviour without requiring an extra dep.
     */
    private fun String.normaliseRuleId(): String =
        this
            .trim()
            .lowercase()
            .replace("_", "-")
            .replace(NON_ID_CHAR_REGEX, "-")
            .replace(HYPHEN_RUN_REGEX, "-")
            .trim('-')
}
