package io.karandash.platforms.bitbucket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class BitbucketPullRequest(
    val id: Int,
    val title: String,
    val description: String? = null,
    val state: String,
    /** Bitbucket Cloud reports draft pull requests with this flag; `state` stays `OPEN`. */
    val draft: Boolean = false,
    val author: BitbucketUser,
    val source: BitbucketBranch,
    val destination: BitbucketBranch,
    @SerialName("created_on")
    val createdOn: String,
    @SerialName("updated_on")
    val updatedOn: String,
    @SerialName("comment_count")
    val commentCount: Int = 0,
    @SerialName("task_count")
    val taskCount: Int = 0,
    val participants: List<BitbucketParticipant> = emptyList(),
    val reviewers: List<BitbucketUser> = emptyList()
)

@Serializable
data class BitbucketUser(
    val uuid: String,
    val username: String? = null,
    @SerialName("display_name")
    val displayName: String,
    val nickname: String? = null,
    @SerialName("account_id")
    val accountId: String? = null,
)

@Serializable
data class BitbucketBranch(
    val branch: BitbucketBranchInfo,
    val commit: BitbucketCommitRef,
    val repository: BitbucketRepository
)

@Serializable
data class BitbucketBranchInfo(
    val name: String
)

@Serializable
data class BitbucketCommitRef(
    val hash: String
)

@Serializable
data class BitbucketRepository(
    val uuid: String,
    val name: String,
    @SerialName("full_name")
    val fullName: String
)

@Serializable
data class BitbucketParticipant(
    val user: BitbucketUser,
    val role: String,
    val approved: Boolean,
    @SerialName("participated_on")
    val participatedOn: String? = null
)

@Serializable
data class BitbucketCommit(
    val hash: String,
    val date: String,
    val message: String,
    val author: BitbucketCommitAuthor
)

@Serializable
data class BitbucketCommitAuthor(
    val raw: String,
    val user: BitbucketUser? = null
)

/** One `GET /workspaces/{workspace}/members` entry. */
@Serializable
data class BitbucketMembership(
    val user: BitbucketUser,
)

@Serializable
data class BitbucketMembershipsResponse(
    override val values: List<BitbucketMembership>,
    override val next: String? = null,
) : BitbucketPagedResponse<BitbucketMembership>

interface BitbucketPagedResponse<T> {
    val values: List<T>
    val next: String?
}

@Serializable
data class BitbucketCommitsResponse(
    override val values: List<BitbucketCommit>,
    override val next: String? = null
) : BitbucketPagedResponse<BitbucketCommit>

@Serializable
data class BitbucketDiffStat(
    val status: String,
    @SerialName("old")
    @Serializable(with = DiffStatPathSerializer::class)
    val oldPath: String? = null,
    @SerialName("new")
    @Serializable(with = DiffStatPathSerializer::class)
    val newPath: String? = null,
    @SerialName("lines_added")
    val linesAdded: Int = 0,
    @SerialName("lines_removed")
    val linesRemoved: Int = 0
)

/** Bitbucket sends diffstat `old`/`new` as `{"path": ..., "type": "commit_file"}` objects; a bare path string is accepted too. */
internal object DiffStatPathSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement = (element as? JsonObject)?.get("path") ?: element
}

@Serializable
data class BitbucketDiffStatsResponse(
    override val values: List<BitbucketDiffStat>,
    override val next: String? = null
) : BitbucketPagedResponse<BitbucketDiffStat>

@Serializable
data class BitbucketFileHistoryEntry(
    val commit: BitbucketFileHistoryCommit,
    val path: String? = null
)

@Serializable
data class BitbucketFileHistoryCommit(
    val hash: String,
    val date: String? = null,
    val message: String? = null,
    val author: BitbucketCommitAuthor? = null
)

@Serializable
data class BitbucketFileHistoryResponse(
    override val values: List<BitbucketFileHistoryEntry>,
    override val next: String? = null
) : BitbucketPagedResponse<BitbucketFileHistoryEntry>

@Serializable
data class BitbucketComment(
    val id: Long,
    val content: BitbucketCommentContent? = null,
    val user: BitbucketUser? = null,
    @SerialName("created_on")
    val createdOn: String? = null,
    @SerialName("updated_on")
    val updatedOn: String? = null,
    val links: BitbucketLinks? = null,
    val inline: BitbucketInlinePosition? = null
)

@Serializable
data class BitbucketInlinePosition(
    val path: String? = null,
    val from: Int? = null,
    val to: Int? = null
)

@Serializable
data class BitbucketCommentContent(
    val raw: String? = null,
    val markup: String? = null,
    val html: String? = null
)

@Serializable
data class BitbucketLinks(
    @SerialName("self")
    val self: List<BitbucketLink>? = null,
    val html: List<BitbucketLink>? = null
)

@Serializable
data class BitbucketLink(
    val href: String
)

@Serializable
data class BitbucketCommentsResponse(
    val pagelen: Int = 0,
    override val values: List<BitbucketComment> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    override val next: String? = null
) : BitbucketPagedResponse<BitbucketComment>

// Request models for API calls

@Serializable
data class PostCommentRequest(
    val content: CommentContentRequest
)

@Serializable
data class CommentContentRequest(
    val raw: String
)

@Serializable
data class PostInlineCommentRequest(
    val content: CommentContentRequest,
    val inline: InlineCommentPosition
)

@Serializable
data class InlineCommentPosition(
    val path: String,
    val to: Int,
    /** First line of a multi-line comment in the new file; [to] is then its last line. */
    @SerialName("start_to")
    val startTo: Int? = null,
)

/** A build status on the PR's commits (`state`: SUCCESSFUL, FAILED, INPROGRESS, STOPPED). */
@Serializable
data class BitbucketCommitStatus(
    val key: String,
    val state: String,
    val name: String? = null,
)

@Serializable
data class BitbucketCommitStatusesResponse(
    override val values: List<BitbucketCommitStatus>,
    override val next: String? = null
) : BitbucketPagedResponse<BitbucketCommitStatus>
