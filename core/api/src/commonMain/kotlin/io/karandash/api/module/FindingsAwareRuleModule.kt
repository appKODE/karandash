package io.karandash.api.module

import io.karandash.api.context.ValidationContext
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonElement

/**
 * Extended [RuleModule] that receives prior findings via [ValidationContext].
 *
 * Modules opt in by implementing this sub-interface. The validation engine detects
 * it at runtime and calls [validateWithFindings] instead of [RuleModule.validate]
 * when a [ValidationContext] is available (pass 2).
 *
 * Regular [RuleModule] implementations are unaffected - the engine falls back to
 * [validate] when the module does not implement this interface or when no context
 * is available (pass 1).
 *
 * Usage:
 * ```kotlin
 * class MyAwareRule : AbstractRuleModule(), FindingsAwareRuleModule {
 *     override suspend fun validateWithFindings(
 *         ruleId: String,
 *         mr: MergeRequest,
 *         configElement: JsonElement,
 *         context: ValidationContext,
 *     ): List<Finding> {
 *         if (context.hasFindings("security")) {
 *             // skip - security rule already covered this concern
 *             return emptyList()
 *         }
 *         return validate(ruleId, mr, configElement)
 *     }
 * }
 * ```
 */
public interface FindingsAwareRuleModule : RuleModule {
    /**
     * Validates the merge request with access to prior findings from pass 1.
     *
     * Called by the engine in pass 2 when a [ValidationContext] is available.
     * Implementations can use [context] to decide whether to skip, enhance,
     * or run with additional information from earlier rules.
     *
     * @param ruleId The rule identifier being evaluated.
     * @param mr The merge request to validate.
     * @param configElement Rule-specific configuration as [JsonElement].
     * @param context Immutable context containing pass-1 findings.
     * @return List of findings, or empty list to skip.
     */
    public suspend fun validateWithFindings(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
        context: ValidationContext,
    ): List<Finding>
}
