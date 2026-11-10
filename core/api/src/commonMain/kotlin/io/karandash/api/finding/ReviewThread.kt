package io.karandash.api.finding

import kotlinx.serialization.Serializable

/**
 * A review thread on a merge request as the platform reports it, with every note in order.
 *
 * @property id Platform thread id (GitLab discussion id).
 * @property notes Notes in the thread, oldest first; the first one opened the thread.
 * @property resolved Whether the platform marks the whole thread resolved.
 * @property position Diff position of an inline thread; null for a general one.
 */
@Serializable
public data class ReviewThread(
    val id: String,
    val notes: List<ReviewNote>,
    val resolved: Boolean = false,
    val position: ReviewThreadPosition? = null,
)

/**
 * One note (comment) in a [ReviewThread].
 *
 * @property id Platform note id.
 * @property author Username of the author; null when the platform hides it.
 * @property createdAt Creation time as the platform's ISO-8601 string.
 * @property system Whether the platform generated the note (e.g. "changed this line").
 * @property resolvedBy Username of whoever resolved the note's thread.
 * @property awards Reactions on the note.
 */
@Serializable
public data class ReviewNote(
    val id: String,
    val body: String,
    val author: String? = null,
    val createdAt: String? = null,
    val system: Boolean = false,
    val resolvable: Boolean = false,
    val resolved: Boolean = false,
    val resolvedBy: String? = null,
    val awards: List<ReviewAward> = emptyList(),
)

/**
 * A reaction on a [ReviewNote].
 *
 * @property name Platform emoji name (GitLab `thumbsdown`, `thumbsup`).
 * @property user Username of whoever reacted.
 */
@Serializable
public data class ReviewAward(
    val name: String,
    val user: String? = null,
)

/** Diff position of an inline [ReviewThread]; lines are 1-based, null on the side the line does not exist. */
@Serializable
public data class ReviewThreadPosition(
    val newPath: String? = null,
    val oldPath: String? = null,
    val newLine: Int? = null,
    val oldLine: Int? = null,
)
