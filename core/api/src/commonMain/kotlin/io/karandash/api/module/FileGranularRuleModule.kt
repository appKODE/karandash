package io.karandash.api.module

import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonElement

/**
 * Extended [RuleModule] for rules that validate each file independently.
 *
 * The engine caches each file's findings per rule, keyed on the file change (path, diff, content)
 * and the rule config: when the MR changes, only files whose change differs are re-validated and
 * cached findings for the others are reused. [validateFile] must therefore depend only on [FileChange]
 * and config, not on other MR fields.
 *
 * Only implement this interface when the rule validates each file in isolation
 * (no cross-file aggregation). Rules that need aggregate totals across all files
 * (e.g., size-limits) should use standard [RuleModule] instead.
 */
public interface FileGranularRuleModule : RuleModule {
    /**
     * Validates a single file change.
     *
     * Called once per changed file whose change or config is not already cached.
     *
     * @param ruleId The rule identifier being evaluated.
     * @param file The individual file change to validate.
     * @param mr The full merge request (context only; the cache does not key on it).
     * @param config Rule-specific configuration as [JsonElement].
     * @return List of findings for this file, or empty list if the file passes.
     */
    public suspend fun validateFile(
        ruleId: String,
        file: FileChange,
        mr: MergeRequest,
        config: JsonElement
    ): List<Finding>
}
