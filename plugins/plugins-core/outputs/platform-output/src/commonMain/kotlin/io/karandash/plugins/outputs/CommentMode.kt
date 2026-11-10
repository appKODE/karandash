package io.karandash.plugins.outputs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `comments`: which MR comments the platform output posts. [ALL] (the default) posts the summary and the inline
 * comments; [NONE] posts nothing, for a team that reads results only from a check, a status or a report artifact.
 */
@Serializable
enum class CommentMode(
    val summary: Boolean,
    val inline: Boolean,
) {
    @SerialName("all")
    ALL(summary = true, inline = true),

    @SerialName("summary")
    SUMMARY(summary = true, inline = false),

    @SerialName("inline")
    INLINE(summary = false, inline = true),

    @SerialName("none")
    NONE(summary = false, inline = false),
}
