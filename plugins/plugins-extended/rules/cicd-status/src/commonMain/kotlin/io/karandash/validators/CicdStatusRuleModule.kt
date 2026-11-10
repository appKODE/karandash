@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.platform.MergeRequestField
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule

class CicdStatusRuleModule : AbstractTypedSingleRuleModule<CicdStatusConfig>(CicdStatusConfig.serializer()) {
    companion object {
        const val RULE_ID = "cicdStatus"
    }

    override val defaultEnabled: Boolean = false
    override val ruleId: String = RULE_ID
    override val explanation: String =
        "CI/CD pipeline status checks ensure that automated tests, builds, and other quality gates pass before " +
            "code can be merged, preventing broken code from entering the main branch."

    override fun requiredMergeRequestFields(ruleId: String): Set<MergeRequestField> = setOf(MergeRequestField.CI_STATUS)

    override val name: String = "CI/CD Status Rule"
    override val description: String = "Validates that required CI/CD pipeline checks have passed."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: CicdStatusConfig
    ): List<Finding> = CicdStatusValidator(config).validate(mr).map { it.withFixedText(CicdStatusText.entries) }
}
