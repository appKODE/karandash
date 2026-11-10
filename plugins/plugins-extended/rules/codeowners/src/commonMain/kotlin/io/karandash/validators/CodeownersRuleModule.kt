package io.karandash.validators

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.exception.KarandashException
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule
import kotlinx.io.IOException

@OptIn(ExperimentalKarandashApi::class)
class CodeownersRuleModule : AbstractTypedSingleRuleModule<CodeownersConfig>(CodeownersConfig.serializer()) {
    companion object {
        const val RULE_ID = "codeowners"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "CODEOWNERS validation ensures that the appropriate people or teams have reviewed changes to critical " +
            "parts of the codebase, maintaining code quality and ownership."

    override val name: String = "Codeowners Rule"
    override val description: String = "Validates that changed files have CODEOWNERS coverage and required approvals."

    /** Owners are satisfied only by approvals, so without them every owned file would be reported. */
    override fun requiredMergeRequestFields(ruleId: String): Set<MergeRequestField> = setOf(MergeRequestField.APPROVALS)

    override fun doValidateTyped(
        mr: MergeRequest,
        config: CodeownersConfig
    ): List<Finding> = CodeownersValidator(config).validate(mr).map { it.withFixedText(CodeownersText.entries) }

    /**
     * Reads CODEOWNERS from the MR's target branch through the run's platform when it can
     * ([RunServiceKeys.REPOSITORY_FILE]); the working copy is the source branch, which the MR author controls.
     * Offline runs keep reading the local file.
     */
    override suspend fun validateTyped(
        ruleId: String,
        mr: MergeRequest,
        config: CodeownersConfig,
    ): List<Finding> {
        if (ruleId != RULE_ID || !config.requireOwnerApproval) return emptyList()
        val reader = RunScopedServices.current()?.get(RunServiceKeys.REPOSITORY_FILE)
            ?: return super.validateTyped(ruleId, mr, config)
        val fetched = try {
            CodeownersValidator(config)
                .candidatePaths()
                .associateWith { reader.read(mr.targetBranch, it) }
        } catch (e: KarandashException) {
            return listOf(CodeownersValidator.unreadableFinding("could not read CODEOWNERS from ${mr.targetBranch} (${e.message})"))
        } catch (e: IOException) {
            return listOf(CodeownersValidator.unreadableFinding("could not read CODEOWNERS from ${mr.targetBranch} (${e.message})"))
        }
        return CodeownersValidator(config) { fetched[it] }.validate(mr).map { it.withFixedText(CodeownersText.entries) }
    }
}
