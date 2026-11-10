package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.context.EnrichmentContext
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.utils.EnrichmentGlobMatcher

/** Per-rule enrichment isolation for [AiRuleModule]: what each AI rule may see and what it is missing. */
internal class AiRuleEnrichmentScope(
    private val registry: ModuleRegistry,
) {
    /**
     * Narrows [moduleEnrichment] (already bounded, module-wide, to the union of every AI
     * rule's declared interest -- see [AiRuleModule.supportedEnrichment]) down to exactly what [ruleId]
     * is entitled to: its own analyzer's declared
     * [io.karandash.api.ai.AiAnalyzerModule.supportedEnrichment] (Path A, a hardcoded floor)
     * unioned with any `rule-enrichment:` binding configured specifically for this rule ID
     * (Path B, config-side broadening -- see [AiRuleInitState.ruleEnrichmentBindingPrefixes],
     * delivered via [io.karandash.api.module.RuleModule.initialize]'s two-parameter overload
     * since a module's ambient [io.karandash.api.PluginContext.enrichment] only carries one module-wide
     * [EnrichmentContext], not a per-contributed-rule breakdown).
     *
     * When NEITHER source names anything for this specific rule, the result is
     * [EnrichmentContext.EMPTY] -- *not* a fallback to the module-wide set. Falling back would
     * leak a sibling rule's data (e.g. `design-doc-drift`'s jira/confluence) to a rule that
     * was never configured to see it (e.g. `review-comments`, if only `design-doc-drift` had
     * a binding), purely because they share the same `AiRuleModule` instance internally.
     */
    fun scoped(
        ruleId: String,
        moduleEnrichment: EnrichmentContext,
        ruleEnrichmentBindingPrefixes: Map<String, List<String>>,
    ): EnrichmentContext {
        val analyzerInterest = registry.findAiAnalyzer(ruleId)?.supportedEnrichment
            ?: CHECKS_ENRICHMENT.takeIf { ruleId == AiRuleIds.CHECKS }.orEmpty()
        val bindingPrefixes = ruleEnrichmentBindingPrefixes[ruleId].orEmpty()
        val patterns = (analyzerInterest + bindingPrefixes).distinct()
        if (patterns.isEmpty()) return EnrichmentContext.EMPTY
        val matchedIds = EnrichmentGlobMatcher.resolve(patterns, moduleEnrichment.availableIds)
        return moduleEnrichment.filterIds(matchedIds)
    }

    /**
     * Returns the [io.karandash.api.ai.AiAnalyzerModule.requiredEnrichment] prefixes for
     * [ruleId] that match no ID in [scopedEnrichment] -- i.e. what's missing. Empty means the
     * analyzer has everything it declared as required (or declares nothing required at all).
     */
    fun missingRequired(
        ruleId: String,
        scopedEnrichment: EnrichmentContext,
    ): List<String> =
        registry
            .findAiAnalyzer(ruleId)
            ?.requiredEnrichment
            .orEmpty()
            .filter { required -> EnrichmentGlobMatcher.resolve(listOf(required), scopedEnrichment.availableIds).isEmpty() }

    private companion object {
        /** The tickets the pre-merge checklist turns into rows (`ai.checks-ticket-compliance`). */
        val CHECKS_ENRICHMENT = listOf(
            EnrichmentKeys.JIRA_TICKETS,
            EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA,
            EnrichmentKeys.GITHUB_ISSUES,
            EnrichmentKeys.GITLAB_ISSUES,
        )
    }
}
