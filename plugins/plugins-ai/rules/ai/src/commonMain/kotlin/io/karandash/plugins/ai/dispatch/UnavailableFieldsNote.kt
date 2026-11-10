@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiContextBlock
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys

/**
 * Tells the model which merge-request fields the run's platform never fills, so an empty value (no approvals, no
 * CI status) is not read as "none" and reported.
 */
internal object UnavailableFieldsNote {
    const val SOURCE = "unavailable-fields"
    private const val PRIORITY = 90

    /** The run's [RunServiceKeys.UNAVAILABLE_FIELDS]; empty outside a run or when the platform fills everything. */
    suspend fun current(): Set<MergeRequestField> = RunScopedServices.current()?.get(RunServiceKeys.UNAVAILABLE_FIELDS).orEmpty()

    fun block(fields: Set<MergeRequestField>): AiContextBlock? =
        if (fields.isEmpty()) {
            null
        } else {
            AiContextBlock(
                heading = "#### Data this platform does not provide",
                content = "Not available on this platform: ${fields.joinToString { it.displayName }}. " +
                    "An empty value of these means unknown, not none: do not report it as missing.",
                priority = PRIORITY,
                source = SOURCE,
            )
        }
}
