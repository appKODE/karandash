@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.fixing

import io.karandash.api.module.FixHandler
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.PlatformOperation
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * built-in handler for the `remove-review` custom fix (emitted by the approval rule's
 * `noSelfApproval` with `data = {"username": "..."}` when the MR author is a reviewer): removes the
 * user through [PlatformServiceModule.removeReviewers] when the platform declares
 * [PlatformOperation.MANAGE_REVIEWERS]; otherwise applies nothing.
 */
internal object RemoveReviewFixHandler : FixHandler {
    const val TYPE: String = "remove-review"

    override fun canHandle(suggestion: FixSuggestion): Boolean = suggestion is FixSuggestion.Custom && suggestion.type == TYPE

    override suspend fun apply(
        suggestion: FixSuggestion,
        finding: Finding,
        mutator: PlatformServiceModule,
        projectId: String,
        mrId: String,
    ): String? {
        val username = ((suggestion as? FixSuggestion.Custom)?.data as? JsonObject)
            ?.get("username")
            ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (username.isNullOrBlank() || !mutator.supports(PlatformOperation.MANAGE_REVIEWERS)) return null
        mutator.removeReviewers(projectId, mrId, listOf(username)).getOrThrow()
        return "Removed $username from reviewers"
    }
}
