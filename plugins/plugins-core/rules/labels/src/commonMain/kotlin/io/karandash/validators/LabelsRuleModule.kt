@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.context.ConditionContext
import io.karandash.api.platform.MergeRequestField
import io.karandash.conditions.CommonPredicates
import io.karandash.conditions.PredicateRegistry
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class LabelsRuleModule : AbstractTypedSingleRuleModule<LabelsConfig>(LabelsConfig.serializer()) {
    companion object {
        const val RULE_ID = "labels"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Labels help categorize and organize merge requests, making it easier to track, filter, and report on " +
            "different types of changes across the project."

    override fun requiredMergeRequestFields(ruleId: String): Set<MergeRequestField> = setOf(MergeRequestField.LABELS)

    override val name: String = "Labels Rule"
    override val description: String = "Validates that required labels are applied to the MR."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: LabelsConfig,
    ): List<Finding> = LabelsValidator(config).validate(mr)

    override fun registerPredicates(registry: PredicateRegistry<ConditionContext>) {
        registry.register(CommonPredicates.labelExists)
    }
}
