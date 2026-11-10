package io.karandash.platforms.azuredevops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AzurePullRequest(
    val pullRequestId: Int,
    val title: String,
    val description: String? = null,
    val status: String,
    val createdBy: AzureUser? = null,
    val sourceRefName: String = "",
    val targetRefName: String = "",
    @SerialName("creationDate")
    val creationDate: String = "",
    @SerialName("closedDate")
    val closedDate: String? = null,
    val labels: List<AzureLabel> = emptyList(),
    val reviewers: List<AzureReviewer> = emptyList(),
    val url: String? = null,
    val lastMergeSourceCommit: AzureDevOpsCommitRef? = null,
)

@Serializable
data class AzureUser(
    val id: String? = null,
    val displayName: String? = null,
    val uniqueName: String? = null,
    val imageUrl: String? = null
)

@Serializable
data class AzureLabel(
    val id: String,
    val name: String,
    val active: Boolean = true
)

@Serializable
data class AzureReviewer(
    val reviewerId: String? = null,
    val displayName: String? = null,
    val uniqueName: String? = null,
    val vote: Int = 0,
    val isContainer: Boolean = false,
    val isRequired: Boolean = false
)

@Serializable
data class AzureComment(
    val id: Int? = null,
    val threadId: Int? = null,
    val comments: List<AzureCommentContent> = emptyList(),
    val status: String? = null,
    val createdDate: String? = null,
    val url: String? = null
)

@Serializable
data class AzureCommentContent(
    val id: Int? = null,
    val content: String? = null,
    val author: AzureUser? = null,
    @SerialName("publishedDate")
    val publishedDate: String? = null,
    val url: String? = null,
    /** `text`, `codeChange` or `system`. */
    val commentType: String? = null,
)

@Serializable
data class AzureThreadsResponse(
    val value: List<AzureThread> = emptyList(),
    val count: Int = 0
)

@Serializable
data class AzureThread(
    val id: Int? = null,
    val comments: List<AzureCommentContent> = emptyList(),
    val status: String? = null,
    @SerialName("publishedDate")
    val publishedDate: String? = null,
    val url: String? = null,
    @SerialName("threadContext")
    val threadContext: AzureThreadContext? = null
)

// Request models for API calls

@Serializable
data class PostCommentRequest(
    val comments: List<CommentContentRequest>,
    val status: String
)

@Serializable
data class CommentContentRequest(
    val content: String
)

@Serializable
data class UpdateThreadRequest(
    val content: String
)

@Serializable
data class AddLabelRequest(
    val name: String
)

@Serializable
data class AzureThreadContext(
    @SerialName("filePath")
    val filePath: String,
    // Null on a file-level thread and on one anchored to the left (deleted) side only.
    @SerialName("rightFileStart")
    val rightFileStart: AzureFilePosition? = null,
    @SerialName("rightFileEnd")
    val rightFileEnd: AzureFilePosition? = null
)

@Serializable
data class AzureFilePosition(
    val line: Int,
    val offset: Int
)

@Serializable
data class PostInlineCommentRequest(
    val comments: List<InlineCommentContentRequest>,
    val status: String,
    @SerialName("threadContext")
    val threadContext: AzureThreadContext
)

@Serializable
data class InlineCommentContentRequest(
    val content: String,
    @SerialName("commentType")
    val commentType: Int
)

/** A `{count, value:[{id}]}` list of identities: PR reviewers or an identities search. */
@Serializable
data class AzureIdentityList(
    val value: List<AzureIdentityRef> = emptyList(),
)

@Serializable
data class AzureIdentityRef(
    val id: String,
)
