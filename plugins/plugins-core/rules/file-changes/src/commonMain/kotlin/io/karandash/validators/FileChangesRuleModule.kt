@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.context.ConditionContext
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.run.RunContext
import io.karandash.conditions.CommonPredicates
import io.karandash.conditions.PredicateRegistry
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.sdk.config.deserializeConfigReporting
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class FileChangesRuleModule : AbstractTypedSingleRuleModule<FileChangesConfig>(FileChangesConfig.serializer()) {
    companion object {
        const val RULE_ID = "fileChanges"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "This rule validates the scope and nature of file changes, ensuring modifications are appropriate and " +
            "don't include unexpected or prohibited file types."

    override val name: String = "File Changes Rule"
    override val description: String = "Validates file change restrictions (blocked files, required files, glob patterns)."

    override val supportedEnrichment: List<String> = listOf(EnrichmentKeys.CI_COVERAGE_GAPS)

    /** Approvals and labels only when a configured key reads them, so `blockedPaths` alone still runs without approvals. */
    override fun requiredMergeRequestFields(
        ruleId: String,
        config: JsonElement,
    ): Set<MergeRequestField> {
        val parsed = deserializeConfigReporting(configSerializer, config).first
        val rules = parsed.patterns
        return buildSet {
            if (parsed.requireApproval != null || rules.any { it.requireApproval != null || it.requireNonAuthorApproval }) {
                add(MergeRequestField.APPROVALS)
            }
            if (parsed.requireLabels != null || rules.any { it.requireLabels != null }) add(MergeRequestField.LABELS)
        }
    }

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
        run: RunContext,
    ): List<Finding> {
        val findings = validate(ruleId, mr, configElement)
        if (ruleId != RULE_ID) return findings
        val gaps = run.enrichment.get(EnrichmentKeys.CI_COVERAGE_GAPS) as? JsonObject
        val (config, _) = deserializeConfigReporting(configSerializer, configElement)
        return findings + FileChangesValidator(config).coverageFindings(mr, gaps)
    }

    override fun doValidateTyped(
        mr: MergeRequest,
        config: FileChangesConfig,
    ): List<Finding> = FileChangesValidator(config).validate(mr).map { it.withFixedText(FileChangesText.entries) }

    override fun registerPredicates(registry: PredicateRegistry<ConditionContext>) {
        registry.register(CommonPredicates.filePathMatches)
        registry.register(CommonPredicates.hasChangesIn)
    }
}
