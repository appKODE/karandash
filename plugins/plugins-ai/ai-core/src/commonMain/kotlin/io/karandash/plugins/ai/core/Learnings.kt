package io.karandash.plugins.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `ai.learnings`: the review-feedback learnings file written by `calibrate --learn`. When [enabled] the AI review
 * reads [file] from the MR base (the target branch, never the head, so an MR cannot quiet its own review), sends it
 * as T0 guidance and applies it to the findings. [minRejections] is the rejected-finding floor `calibrate --learn`
 * needs before it writes an entry.
 */
@Serializable
data class LearningsConfig(
    val enabled: Boolean = true,
    val file: String = DEFAULT_FILE,
    val minRejections: Int = DEFAULT_MIN_REJECTIONS,
) {
    companion object {
        const val DEFAULT_FILE: String = ".karandash/learnings.yaml"
        const val DEFAULT_MIN_REJECTIONS: Int = 5
    }
}

/** What a [Learning] does to a matching finding. */
@Serializable
enum class LearningAction {
    /** The finding is dropped. */
    @SerialName("suppress")
    SUPPRESS,

    /** The finding is lowered one severity level. */
    @SerialName("downgrade")
    DOWNGRADE,
}

/** Labelled review outcomes behind a [Learning]. */
@Serializable
data class LearningEvidence(
    val rejected: Int,
    val accepted: Int,
)

/** One learnings-file entry: findings of [rule] (under the [path] glob, when set) get [action]. */
@Serializable
data class Learning(
    val rule: String,
    val path: String? = null,
    val action: LearningAction,
    val evidence: LearningEvidence,
)
