package io.karandash.helpers.output

import kotlinx.serialization.Serializable

/**
 * What the AI review did this run, set by the pipeline on outputs that post to the MR: the status output publishes it
 * as its own `<name>/ai` status, so a provider outage shows in the pipeline widget and not only in the comment.
 *
 * @property line The status line, e.g. `AI: unavailable - 12/12 calls failed` or `AI: off by policy 'docs'`.
 * @property passed False when a provider call failed this run.
 */
@Serializable
data class AiRunStatus(
    val line: String,
    val passed: Boolean,
)
