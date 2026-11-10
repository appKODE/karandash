@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.fixing

import io.karandash.api.module.FixHandler
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/**
 * Describes a single fix action that can be applied to a merge request.
 */
@Serializable
data class FixAction(
    val finding: Finding,
    val description: String,
    val type: FixType,
    val fixSuggestion: FixSuggestion,
)

enum class FixType {
    ADD_LABEL,
    REMOVE_LABEL,
    UNSET_DRAFT,
    UPDATE_TITLE,
    UPDATE_DESCRIPTION,
    CUSTOM,
}

/**
 * Result of applying a fix action.
 *
 * @param skipped true when the action was not attempted (no [FixHandler] for a custom type, platform lacks the
 *   capability, or the handler applied nothing). A skipped action is reported but is not a failure.
 */
@Serializable
data class FixResult(
    val action: FixAction,
    val success: Boolean,
    val error: String? = null,
    val skipped: Boolean = false,
)

/**
 * Determines which validation findings can be auto-fixed and applies safe, mechanical fixes
 * via the provider API. Fix suggestions are provided structurally by rules via
 * [Finding.fixSuggestions], so AutoFixer does not need to know about specific rule IDs
 * or parse human-readable messages.
 *
 * @param confidenceMetadataKey metadata key used to read AI confidence from findings.
 *   Defaults to `"confidence"` which matches `AiFindingMetadataKeys.CONFIDENCE`.
 */
class AutoFixer(
    private val mutator: PlatformServiceModule,
    private val projectId: String,
    private val mrId: String,
    private val aiConfidenceThreshold: Double = DEFAULT_AI_CONFIDENCE_THRESHOLD,
    private val confidenceMetadataKey: String = DEFAULT_CONFIDENCE_METADATA_KEY,
    fixHandlers: List<FixHandler> = emptyList(),
) {
    /** Plugin handlers first so they can take over a built-in custom type. */
    private val customHandlers: List<FixHandler> = fixHandlers + RequestReviewFixHandler + RemoveReviewFixHandler

    init {
        require(aiConfidenceThreshold in 0.0..1.0) {
            "aiConfidenceThreshold must be between 0.0 and 1.0, got $aiConfidenceThreshold"
        }
    }

    /**
     * Analyze findings and return the list of fixable actions.
     * Processes [Finding.fixSuggestions] generically without inspecting rule IDs or message text.
     */
    fun analyzeFixable(findings: List<Finding>): List<FixAction> {
        val actions = mutableListOf<FixAction>()

        for (finding in findings) {
            if (!isAboveConfidenceThreshold(finding)) continue

            for (suggestion in finding.fixSuggestions) {
                val action = toFixAction(finding, suggestion)
                if (action != null) {
                    actions.add(action)
                }
            }
        }

        return actions
    }

    /**
     * Apply the given fix actions via the provider API.
     * Returns a result for each action indicating success or failure.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun applyFixes(actions: List<FixAction>): List<FixResult> {
        val labelsToAdd = actions
            .filter { it.type == FixType.ADD_LABEL }
            .map { (it.fixSuggestion as FixSuggestion.AddLabel).label }
        val labelsToRemove = actions
            .filter { it.type == FixType.REMOVE_LABEL }
            .map { (it.fixSuggestion as FixSuggestion.RemoveLabel).label }
        val shouldUnsetDraft = actions.any { it.type == FixType.UNSET_DRAFT }
        val titleActions = actions.filter { it.type == FixType.UPDATE_TITLE }
        val descriptionActions = actions.filter { it.type == FixType.UPDATE_DESCRIPTION }

        val parallelResults = coroutineScope {
            val deferred = mutableListOf<kotlinx.coroutines.Deferred<List<FixResult>>>()

            if (labelsToAdd.isNotEmpty()) {
                deferred.add(
                    async {
                        try {
                            mutator.addLabels(projectId, mrId, labelsToAdd)
                            actions
                                .filter { it.type == FixType.ADD_LABEL }
                                .map { FixResult(action = it, success = true) }
                        } catch (e: Exception) {
                            // boundary: platform mutator call; failure becomes a FixResult
                            e.rethrowCancellation()
                            actions
                                .filter { it.type == FixType.ADD_LABEL }
                                .map { failed(it, e) }
                        }
                    }
                )
            }

            if (labelsToRemove.isNotEmpty()) {
                deferred.add(
                    async {
                        try {
                            mutator.removeLabels(projectId, mrId, labelsToRemove)
                            actions
                                .filter { it.type == FixType.REMOVE_LABEL }
                                .map { FixResult(action = it, success = true) }
                        } catch (e: Exception) {
                            // boundary: platform mutator call; failure becomes a FixResult
                            e.rethrowCancellation()
                            actions
                                .filter { it.type == FixType.REMOVE_LABEL }
                                .map { failed(it, e) }
                        }
                    }
                )
            }

            if (shouldUnsetDraft || titleActions.isNotEmpty()) {
                // the draft change runs after the title update, so the platform sees the final title
                deferred.add(
                    async {
                        val titleResults = applySingleValue(
                            titleActions,
                            { (it.fixSuggestion as FixSuggestion.SetTitle).title },
                        ) { mutator.updateTitle(projectId, mrId, it) }
                        val draftResults = if (shouldUnsetDraft) {
                            applySingleValue(actions.filter { it.type == FixType.UNSET_DRAFT }, { "ready" }) {
                                mutator.setDraft(projectId, mrId, false)
                            }
                        } else {
                            emptyList()
                        }
                        titleResults + draftResults
                    }
                )
            }

            if (descriptionActions.isNotEmpty()) {
                deferred.add(
                    async {
                        applySingleValue(
                            descriptionActions,
                            { (it.fixSuggestion as FixSuggestion.SetDescription).description },
                        ) { mutator.updateDescription(projectId, mrId, it) }
                    }
                )
            }

            deferred.awaitAll()
        }

        return parallelResults.flatten() + actions.filter { it.type == FixType.CUSTOM }.map { applyCustomFix(it) }
    }

    /** A failed fix; an operation the platform does not support is skipped, not failed. */
    private fun failed(
        action: FixAction,
        e: Exception,
    ): FixResult = FixResult(action = action, success = false, error = e.message, skipped = e is UnsupportedOperationException)

    /**
     * a single-valued field (title, description) is written once. The first action wins, actions asking for
     * the same value share its result, and actions asking for a different value are reported as skipped conflicts.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun applySingleValue(
        group: List<FixAction>,
        valueOf: (FixAction) -> String,
        apply: suspend (String) -> Unit,
    ): List<FixResult> {
        val first = group.firstOrNull() ?: return emptyList()
        val chosen = valueOf(first)
        val applied = try {
            apply(chosen)
            FixResult(action = first, success = true)
        } catch (e: Exception) {
            // boundary: platform mutator call; failure becomes a FixResult
            e.rethrowCancellation()
            failed(first, e)
        }
        return group.map { action ->
            if (valueOf(action) == chosen) {
                applied.copy(action = action)
            } else {
                FixResult(
                    action = action,
                    success = false,
                    error = "conflicts with another fix for the same field; applied the first",
                    skipped = true,
                )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: FixHandler is plugin code and may throw anything
    private suspend fun applyCustomFix(action: FixAction): FixResult {
        val suggestion = action.fixSuggestion
        val handler = customHandlers.firstOrNull { it.canHandle(suggestion) }
            ?: return FixResult(
                action = action,
                success = false,
                error = "no fix handler registered for custom fix type '${(suggestion as? FixSuggestion.Custom)?.type}'",
                skipped = true,
            )
        return try {
            val applied = handler.apply(suggestion, action.finding, mutator, projectId, mrId)
            if (applied == null) {
                FixResult(action = action, success = false, error = "fix handler applied nothing", skipped = true)
            } else {
                FixResult(action = action, success = true)
            }
        } catch (e: UnsupportedOperationException) {
            // boundary: custom fix handler is plugin code
            FixResult(action = action, success = false, error = e.message, skipped = true)
        } catch (e: Exception) {
            // boundary: custom fix handler is plugin code
            e.rethrowCancellation()
            FixResult(action = action, success = false, error = e.message)
        }
    }

    private fun isAboveConfidenceThreshold(finding: Finding): Boolean {
        val confidence = finding.metadata[confidenceMetadataKey]?.toDoubleOrNull()
        return confidence == null || confidence >= aiConfidenceThreshold
    }

    companion object {
        const val DEFAULT_AI_CONFIDENCE_THRESHOLD: Double = 0.8
        const val DEFAULT_CONFIDENCE_METADATA_KEY: String = "confidence"

        internal fun toFixAction(
            finding: Finding,
            suggestion: FixSuggestion
        ): FixAction? =
            when (suggestion) {
                is FixSuggestion.AddLabel -> FixAction(
                    finding = finding,
                    description = "Add label '${suggestion.label}'",
                    type = FixType.ADD_LABEL,
                    fixSuggestion = suggestion,
                )

                is FixSuggestion.RemoveLabel -> FixAction(
                    finding = finding,
                    description = "Remove label '${suggestion.label}'",
                    type = FixType.REMOVE_LABEL,
                    fixSuggestion = suggestion,
                )

                is FixSuggestion.SetDraft -> if (!suggestion.draft) {
                    FixAction(
                        finding = finding,
                        description = "Mark MR as ready (remove draft status)",
                        type = FixType.UNSET_DRAFT,
                        fixSuggestion = suggestion,
                    )
                } else {
                    null
                }

                is FixSuggestion.SetTitle -> if (suggestion.title.isNotEmpty()) {
                    FixAction(
                        finding = finding,
                        description = "Update MR title to: \"${suggestion.title}\"",
                        type = FixType.UPDATE_TITLE,
                        fixSuggestion = suggestion,
                    )
                } else {
                    null
                }

                is FixSuggestion.SetDescription -> if (suggestion.description.isNotEmpty()) {
                    FixAction(
                        finding = finding,
                        description = "Update MR description",
                        type = FixType.UPDATE_DESCRIPTION,
                        fixSuggestion = suggestion,
                    )
                } else {
                    null
                }

                is FixSuggestion.Custom -> FixAction(
                    finding = finding,
                    description = suggestion.description.ifEmpty { "Custom fix: ${suggestion.type}" },
                    type = FixType.CUSTOM,
                    fixSuggestion = suggestion,
                )
            }
    }
}
