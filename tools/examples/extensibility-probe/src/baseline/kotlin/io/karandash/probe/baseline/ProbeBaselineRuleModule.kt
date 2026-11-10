package io.karandash.probe.baseline

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.PluginContext
import io.karandash.sdk.module.RuleModule
import kotlinx.serialization.json.JsonElement

/**
 * Baseline probe: a plain [RuleModule] written only against SDK API that exists today.
 *
 * If this source set stops compiling, the probe setup (published SDK, repo, versions) is broken -
 * `verify-extensibility.sh` exits 2 instead of reporting the M-items.
 */
class ProbeBaselineRuleModule : RuleModule {
    override val id: String = ID
    override val name: String = "Extensibility probe — baseline rule"
    override val version: String = "0.0.0"

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun contributedRules(): Set<String> = setOf(RULE_ID)

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding> =
        if (mr.title.isBlank()) {
            listOf(Finding(severity = Severity.INFO, message = "probe: empty title", rule = RULE_ID))
        } else {
            emptyList()
        }

    companion object {
        const val ID = "probe-baseline"
        const val RULE_ID = "probe-baseline-title"
    }
}
