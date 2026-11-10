package io.karandash.validators

import io.karandash.api.module.FileGranularRuleModule
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withFixedText
import io.karandash.sdk.typed.AbstractTypedSingleRuleModule
import kotlinx.serialization.json.JsonElement

/**
 * Rule module that validates dependency files changed in a merge request.
 *
 * Detects unpinned version specifiers, enforces minimum version constraints,
 * and blocks known-vulnerable dependency patterns.
 *
 * Supports: `package.json`, `requirements.txt`, `Cargo.toml`, `go.mod`,
 * `build.gradle`, `build.gradle.kts`, `gradle/libs.versions.toml`.
 *
 * Configuration example:
 * ```yaml
 * rules:
 *   dependency-validation:
 *     warnOnUnpinnedVersions: true
 *     minVersions:
 *       lodash: "4.17.21"
 *     blockedPatterns:
 *       - "log4j:2.14"
 * ```
 */
class DependencyValidationRuleModule :
    AbstractTypedSingleRuleModule<DependencyValidationConfig>(DependencyValidationConfig.serializer()),
    FileGranularRuleModule {
    companion object {
        const val RULE_ID = "dependency-validation"
    }

    override val ruleId: String = RULE_ID
    override val explanation: String =
        "Validates dependency declarations in changed dependency files. " +
            "Detects unpinned versions, enforces minimum version constraints, " +
            "and blocks known-vulnerable dependency patterns."

    override val name: String = "Dependency Validation Rule"
    override val description: String =
        "Checks changed dependency files (package.json, requirements.txt, Cargo.toml, go.mod, " +
            "build.gradle.kts, libs.versions.toml) for unpinned, outdated, or blocked dependencies."

    override fun doValidateTyped(
        mr: MergeRequest,
        config: DependencyValidationConfig,
    ): List<Finding> = DependencyValidator(config).validate(mr).map { it.withFixedText(DependencyText.entries) }

    override suspend fun validateFile(
        ruleId: String,
        file: FileChange,
        mr: MergeRequest,
        config: JsonElement
    ): List<Finding> = validate(ruleId, mr.copy(changes = listOf(file)), config)
}
