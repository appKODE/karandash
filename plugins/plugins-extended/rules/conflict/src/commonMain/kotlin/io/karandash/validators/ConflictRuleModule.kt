@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.platform.MergeRequestField
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class ConflictRuleModule : AbstractTypedSingleRuleModule<ConflictConfig>(ConflictConfig.serializer()) {
    companion object {
        const val RULE_ID = "conflict"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Merge conflicts indicate that the branch has diverged from the target branch in incompatible ways. " +
            "Resolving conflicts ensures a clean merge without lost changes."

    override fun requiredMergeRequestFields(ruleId: String): Set<MergeRequestField> = setOf(MergeRequestField.CONFLICTS)

    override val name: String = "Conflict Rule"
    override val description: String = "Detects merge conflicts in the MR."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: ConflictConfig
    ): List<Finding> = ConflictValidator(config).validate(mr).map { it.withFixedText(ConflictText.entries) }
}
