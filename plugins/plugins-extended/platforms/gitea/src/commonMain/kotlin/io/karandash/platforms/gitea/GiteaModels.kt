package io.karandash.platforms.gitea

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Gitea comment/note response model
 */
@Serializable
data class GiteaComment(
    val id: Long,
    val body: String,
    @SerialName("user") val user: GiteaUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Gitea inline review comment request item
 */
@Serializable
data class GiteaReviewCommentRequest(
    val path: String,
    @SerialName("new_position")
    val newPosition: Int,
    val body: String,
    /** Line on the old side; a reply to an old-side conversation sets it with [newPosition] 0. */
    @SerialName("old_position")
    val oldPosition: Int = 0,
)

/**
 * Gitea pull request review request body
 */
@Serializable
data class GiteaCreateReviewRequest(
    val body: String,
    val event: String,
    val comments: List<GiteaReviewCommentRequest>,
    @SerialName("commit_id")
    val commitId: String? = null
)

/**
 * Gitea pull request review response
 */
@Serializable
data class GiteaReviewResponse(
    val id: Long,
    val body: String? = null,
    val state: String? = null,
    val user: GiteaUser? = null
)

/**
 * Gitea pull request review comment (inline comment on a specific file/line)
 */
@Serializable
data class GiteaPullReviewComment(
    val id: Long,
    val body: String = "",
    val path: String? = null,
    val user: GiteaUser? = null,
    /** Line on the new side, 0 for an old-side comment. */
    val position: Int = 0,
    /** Line on the old side, 0 for a new-side comment. */
    @SerialName("original_position") val originalPosition: Int = 0,
    @SerialName("commit_id") val commitId: String? = null,
    /** Who resolved the conversation; null while it is open. */
    val resolver: GiteaUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** `GET /version`. */
@Serializable
data class GiteaServerVersion(
    val version: String = "",
)
