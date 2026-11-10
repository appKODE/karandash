package io.karandash.platforms.gitea

import io.karandash.api.support.rethrowCancellation
import io.karandash.models.CiJobStatus
import io.karandash.models.MergeRequest
import io.karandash.pagination.PaginationProbe
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.UnifiedDiff
import io.karandash.sdk.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The pull request with its commits, files, reviews, diff and CI jobs, fetched in parallel. */
internal suspend fun GiteaProviderClient.fetchFullPullRequest(
    owner: String,
    repo: String,
    mrId: String,
    logger: Logger,
): MergeRequest =
    coroutineScope {
        val prDeferred = async { fetchPullRequest(owner, repo, mrId) }
        val commitsDeferred = async { PaginationProbe.probe { fetchPullRequestCommits(owner, repo, mrId) } }
        val filesDeferred = async { fetchPullRequestFiles(owner, repo, mrId) }
        val reviewsDeferred = async { fetchPullRequestReviews(owner, repo, mrId) }
        val diffDeferred = async { fetchPullRequestDiff(owner, repo, mrId) }
        val pr = prDeferred.await()
        val ciJobs = fetchCiJobs(owner, repo, pr.head.sha, logger)
        val mr = GiteaAdapter.toMergeRequest(
            pr,
            commitsDeferred.await().first,
            filesDeferred.await(),
            reviewsDeferred.await(),
        )
        val diffs = diffDeferred.await()?.let(UnifiedDiff::splitByFile).orEmpty()
        mr.copy(
            changes = UnifiedDiff.attach(mr.changes, diffs),
            ciStatus = ciJobs?.let { AdapterHelpers.aggregateCiStatus(it) },
            ciJobs = ciJobs.orEmpty(),
            commitsTruncated = commitsDeferred.await().second,
        )
    }

/** Commit statuses of the head commit as CI jobs; null with a warning when the lookup fails. */
@Suppress("TooGenericExceptionCaught")
private suspend fun GiteaProviderClient.fetchCiJobs(
    owner: String,
    repo: String,
    sha: String,
    logger: Logger,
): List<CiJobStatus>? =
    try {
        fetchCombinedStatus(owner, repo, sha).statuses.map { CiJobStatus(it.context, it.status) }
    } catch (e: Exception) {
        // boundary: CI status is optional MR data; a failed lookup must not fail the fetch
        e.rethrowCancellation()
        logger.warn("Gitea: could not fetch commit status for $owner/$repo@$sha: ${e.message}")
        null
    }
