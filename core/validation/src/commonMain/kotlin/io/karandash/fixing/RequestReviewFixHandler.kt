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
 * Built-in handler for the `request-review` custom fix (emitted by the approval rule with
 * `data = {"username": "..."}`): adds the user as a reviewer when the platform declares
 * [PlatformServiceModule.supports] ([PlatformOperation.MANAGE_REVIEWERS]); otherwise applies nothing.
 */
internal object RequestReviewFixHandler : FixHandler {
    const val TYPE: String = "request-review"

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
        mutator.addReviewers(projectId, mrId, listOf(username)).getOrThrow()
        return "Requested review from $username"
    }
}
