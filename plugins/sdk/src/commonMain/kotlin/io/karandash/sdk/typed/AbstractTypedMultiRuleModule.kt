package io.karandash.sdk.typed

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.PluginContext
import kotlinx.serialization.KSerializer

/**
 * Abstract base class for multi-rule modules that use a single typed config [C].
 *
 * Usage:
 * ```kotlin
 * class JiraRuleModule : AbstractTypedMultiRuleModule<JiraConfig>(JiraConfig.serializer()) {
 *     override val id = "io.karandash.rule.jira"
 *     override val name = "Jira Rule Module"
 *     override suspend fun contributedRules() = setOf("jira-status-check", "jira-assignee-check")
 *     override fun doValidateTyped(ruleId: String, mr: MergeRequest, config: JiraConfig) = ...
 * }
 * ```
 *
 * @param C The typed config class.
 */
public abstract class AbstractTypedMultiRuleModule<C : Any>(
    override val configSerializer: KSerializer<C>,
) : TypedRuleModule<C> {
    /**
     * Perform the actual validation for [ruleId] with the typed config.
     *
     * Called only when [ruleId] is in [contributedRules].
     *
     * @param ruleId The rule being validated.
     * @param mr The merge request data.
     * @param config The typed config instance.
     * @return List of findings (empty = pass).
     */
    protected abstract fun doValidateTyped(
        ruleId: String,
        mr: MergeRequest,
        config: C,
    ): List<Finding>

    override suspend fun validateTyped(
        ruleId: String,
        mr: MergeRequest,
        config: C,
    ): List<Finding> {
        if (ruleId !in contributedRules()) return emptyList()
        return doValidateTyped(ruleId, mr, config)
    }

    override val version: String = "1.7.0"

    override val defaultPriority: Int = 10

    override val defaultEnabled: Boolean = true

    override suspend fun initialize(context: PluginContext): Boolean = true
}
