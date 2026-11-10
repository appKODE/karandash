package io.karandash.probe.rule

import io.karandash.api.run.RunContext
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.PluginContext
import io.karandash.sdk.module.RuleModule
import kotlinx.serialization.json.JsonElement

/**
 * Run-context probe: a rule that reads per-run enrichment from [RunContext] instead of the
 * `initialize(context, ruleEnrichmentBindingPrefixes)` re-init plumbing.
 *
 * Red until the SDK adds `RunContext` (core/api `run/RunContext.kt`) and the
 * `RuleModule.validate(ruleId, mr, configElement, run: RunContext)` overload.
 */
class ProbeEnrichmentRuleModule : RuleModule {
    override val id: String = ID
    override val name: String = "Extensibility probe — enrichment rule"
    override val version: String = "0.0.0"

    override val supportedEnrichment: List<String> = listOf(ENRICHMENT_ID)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun contributedRules(): Set<String> = setOf(RULE_ID)

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding> = emptyList()

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
        run: RunContext,
    ): List<Finding> =
        if (run.enrichment.isMissing(ENRICHMENT_ID)) {
            listOf(Finding(severity = Severity.WARNING, message = "probe: $ENRICHMENT_ID missing", rule = RULE_ID))
        } else {
            emptyList()
        }

    companion object {
        const val ID = "probe-enrichment-rule"
        const val RULE_ID = "probe-enrichment"
        const val ENRICHMENT_ID = "probe.ticket"
    }
}
