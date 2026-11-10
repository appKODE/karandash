package io.karandash.platforms.azuredevops

import io.karandash.sdk.Logger

private const val VOTE_APPROVED = 10
private const val VOTE_NONE = 0
private val GUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * Adds ([add]) or removes [names] as reviewers of the pull request. A reviewer already on the pull request is not
 * re-added, since the add call (`PUT reviewers/{id}` with vote 0) would also reset their vote.
 */
internal suspend fun AzureDevOpsClient.updateReviewers(
    repo: Triple<String, String, String>,
    pullRequestId: Int,
    names: List<String>,
    add: Boolean,
) {
    val (organization, project, repositoryId) = repo
    val current = listReviewerIds(organization, project, repositoryId, pullRequestId).map { it.lowercase() }.toSet()
    names.map { identityId(organization, it) }.forEach { id ->
        when {
            add && id.lowercase() !in current -> unapprovePullRequest(organization, project, repositoryId, pullRequestId, id)
            !add && id.lowercase() in current -> removeReviewer(organization, project, repositoryId, pullRequestId, id)
        }
    }
}

/** Sets the authenticated user's vote to approved or to no vote; nothing is written when it already is. */
internal suspend fun AzureDevOpsClient.setOwnApproval(
    repo: Triple<String, String, String>,
    pullRequestId: Int,
    approved: Boolean,
    logger: Logger,
) {
    val (organization, project, repositoryId) = repo
    val vote = getCurrentUserVote(organization, project, repositoryId, pullRequestId)
    when {
        approved && vote == VOTE_APPROVED -> logger.debug("Skipping approve: already approved by current user")
        !approved && (vote == null || vote == VOTE_NONE) -> logger.debug("Skipping unapprove: not currently approved by current user")
        approved -> approvePullRequest(organization, project, repositoryId, pullRequestId, "me")
        else -> unapprovePullRequest(organization, project, repositoryId, pullRequestId, "me")
    }
}

/** A GUID is taken as the identity id; anything else must match exactly one identity. */
private suspend fun AzureDevOpsClient.identityId(
    organization: String,
    name: String,
): String {
    if (GUID.matches(name)) return name
    val ids = findIdentityIds(organization, name)
    require(ids.size == 1) { "Azure DevOps: '$name' matches ${ids.size} identities; pass the identity id" }
    return ids.single()
}
