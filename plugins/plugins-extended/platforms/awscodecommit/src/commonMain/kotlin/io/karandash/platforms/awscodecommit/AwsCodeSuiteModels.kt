package io.karandash.platforms.awscodecommit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AwsPullRequest(
    @SerialName("pullRequestId")
    val pullRequestId: String,
    val title: String,
    val description: String? = null,
    @SerialName("pullRequestStatus")
    val pullRequestStatus: String,
    @SerialName("authorArn")
    val authorArn: String,
    @SerialName("creationDate")
    val creationDate: String,
    @SerialName("lastActivityDate")
    val lastActivityDate: String? = null,
    @SerialName("pullRequestTargets")
    val pullRequestTargets: List<AwsTarget> = emptyList(),
    @SerialName("approvalRules")
    val approvalRules: List<AwsApprovalRule> = emptyList(),
    @SerialName("revisionId")
    val revisionId: String? = null
)

@Serializable
data class AwsTarget(
    @SerialName("repositoryName")
    val repositoryName: String,
    @SerialName("sourceReference")
    val sourceReference: String,
    @SerialName("destinationReference")
    val destinationReference: String,
    @SerialName("destinationCommit")
    val destinationCommit: String? = null,
    @SerialName("sourceCommit")
    val sourceCommit: String? = null,
    @SerialName("mergeMetadata")
    val mergeMetadata: AwsMergeMetadata? = null,
    /** Merge base of the source and destination commits, as GetPullRequest reports it. */
    @SerialName("mergeBase")
    val mergeBase: String? = null
)

@Serializable
data class AwsMergeMetadata(
    @SerialName("isMerged")
    val isMerged: Boolean = false,
    @SerialName("mergedBy")
    val mergedBy: String? = null,
    @SerialName("mergeCommitId")
    val mergeCommitId: String? = null,
    @SerialName("mergeOption")
    val mergeOption: String? = null
)

@Serializable
data class AwsApprovalRule(
    @SerialName("approvalRuleId")
    val approvalRuleId: String? = null,
    @SerialName("approvalRuleName")
    val approvalRuleName: String,
    @SerialName("approvalRuleContent")
    val approvalRuleContent: String? = null,
    @SerialName("ruleContentSha256")
    val ruleContentSha256: String? = null,
    @SerialName("lastModifiedDate")
    val lastModifiedDate: String? = null,
    @SerialName("creationDate")
    val creationDate: String? = null,
    @SerialName("lastModifiedUser")
    val lastModifiedUser: String? = null,
    @SerialName("originApprovalRuleTemplate")
    val originApprovalRuleTemplate: AwsOriginApprovalRuleTemplate? = null
)

@Serializable
data class AwsOriginApprovalRuleTemplate(
    @SerialName("approvalRuleTemplateId")
    val approvalRuleTemplateId: String? = null,
    @SerialName("approvalRuleTemplateName")
    val approvalRuleTemplateName: String? = null
)

@Serializable
data class AwsApproval(
    @SerialName("userArn")
    val userArn: String,
    @SerialName("approvalState")
    val approvalState: String
)

@Serializable
data class AwsCommit(
    @SerialName("commitId")
    val commitId: String,
    @SerialName("treeId")
    val treeId: String? = null,
    @SerialName("parents")
    val parents: List<String> = emptyList(),
    val message: String? = null,
    val author: AwsCommitAuthor? = null,
    val committer: AwsCommitAuthor? = null,
    @SerialName("additionalData")
    val additionalData: String? = null
)

@Serializable
data class AwsCommitAuthor(
    val name: String? = null,
    val email: String? = null,
    val date: String? = null
)

@Serializable
data class AwsDifference(
    @SerialName("beforeBlob")
    val beforeBlob: AwsBlobMetadata? = null,
    @SerialName("afterBlob")
    val afterBlob: AwsBlobMetadata? = null,
    @SerialName("changeType")
    val changeType: String
)

@Serializable
data class AwsBlobMetadata(
    @SerialName("blobId")
    val blobId: String? = null,
    val path: String? = null,
    val mode: String? = null
)

@Serializable
data class AwsApprovalsResponse(
    val approvals: List<AwsApproval> = emptyList()
)

@Serializable
data class AwsBlobResponse(
    /** Base64-encoded blob content. */
    val content: String
)

@Serializable
data class AwsFileResponse(
    /** Base64-encoded file content. */
    val fileContent: String
)

@Serializable
data class AwsCommitDetailsResponse(
    val commit: AwsCommit
)

@Serializable
data class AwsDifferencesResponse(
    val differences: List<AwsDifference> = emptyList(),
    @SerialName("NextToken")
    val nextToken: String? = null,
)

@Serializable
data class AwsComment(
    @SerialName("commentId")
    val commentId: String,
    val content: String? = null,
    @SerialName("authorArn")
    val authorArn: String? = null,
    @SerialName("creationDate")
    val creationDate: String? = null,
    @SerialName("lastModifiedDate")
    val lastModifiedDate: String? = null,
    @SerialName("inReplyTo")
    val inReplyTo: String? = null,
    @SerialName("clientRequestToken")
    val clientRequestToken: String? = null,
    /** The file of an inline comment, copied from its `commentsForPullRequestData` entry's location; null otherwise. */
    val filePath: String? = null,
    /** The line of an inline comment, copied from the same location; null otherwise. */
    val filePosition: Long? = null,
    /** `BEFORE` or `AFTER`: the side [filePosition] counts on, copied from the same location. */
    val relativeFileVersion: String? = null,
)

@Serializable
data class AwsLocation(
    @SerialName("filePath")
    val filePath: String,
    @SerialName("filePosition")
    val filePosition: Long,
    @SerialName("relativeFileVersion")
    val relativeFileVersion: String
)

@Serializable
data class AwsPullRequestIdsResponse(
    val pullRequestIds: List<String> = emptyList(),
    val nextToken: String? = null,
)
