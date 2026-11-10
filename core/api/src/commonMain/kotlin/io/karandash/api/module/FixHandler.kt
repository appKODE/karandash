package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion

/**
 * Handler for applying fix suggestions to merge requests.
 *
 * Built-in handlers cover the 5 standard fix types (AddLabel, RemoveLabel,
 * SetDraft, SetTitle, SetDescription). Plugins can register custom handlers
 * for [FixSuggestion.Custom] types.
 */
@ExperimentalKarandashApi
public interface FixHandler {
    /** Returns true if this handler can process the given suggestion. */
    public fun canHandle(suggestion: FixSuggestion): Boolean

    /**
     * Apply the fix suggestion.
     *
     * @param suggestion The fix suggestion to apply.
     * @param finding The finding that produced the suggestion.
     * @param mutator The mutator module to use for applying changes.
     * @param projectId The project identifier.
     * @param mrId The merge request identifier.
     * @return A description of what was done, or null if nothing was applied.
     */
    public suspend fun apply(
        suggestion: FixSuggestion,
        finding: Finding,
        mutator: PlatformServiceModule,
        projectId: String,
        mrId: String,
    ): String?
}
