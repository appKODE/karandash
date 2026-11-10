package io.karandash.platforms.bitbucket

/**
 * Adds ([add]) or removes [names] from the pull request's reviewers. Bitbucket Cloud replaces the whole list on
 * `PUT`, so the current reviewers are read first; nothing is written when the list would not change.
 */
internal suspend fun BitbucketClient.updateReviewers(
    workspace: String,
    repoSlug: String,
    prId: Int,
    names: List<String>,
    add: Boolean,
) {
    val current = fetchPullRequest(workspace, repoSlug, prId).reviewers.map { it.uuid }
    val wanted = reviewerUuids(workspace, names)
    val updated = if (add) (current + wanted).distinct() else current - wanted.toSet()
    if (updated != current) setReviewers(workspace, repoSlug, prId, updated)
}

/**
 * Account UUIDs of [names]: a `{uuid}` is taken as is; an account id, nickname or username is looked up among the
 * workspace members (Bitbucket Cloud has no username lookup outside the workspace).
 */
private suspend fun BitbucketClient.reviewerUuids(
    workspace: String,
    names: List<String>,
): List<String> {
    val (uuids, others) = names.partition { it.startsWith('{') && it.endsWith('}') }
    if (others.isEmpty()) return uuids
    val members = listWorkspaceMembers(workspace)
    return uuids +
        others.map { name ->
            val member = members.firstOrNull {
                it.accountId == name || it.nickname.equals(name, ignoreCase = true) || it.username.equals(name, ignoreCase = true)
            }
            requireNotNull(member) { "Bitbucket: '$name' is not a member of workspace $workspace" }.uuid
        }
}
