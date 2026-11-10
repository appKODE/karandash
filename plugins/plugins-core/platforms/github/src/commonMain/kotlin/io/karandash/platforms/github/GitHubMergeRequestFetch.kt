package io.karandash.platforms.github

import io.karandash.api.support.rethrowCancellation
import io.karandash.models.CiJobStatus
import io.karandash.models.MergeRequest
import io.karandash.pagination.PaginationProbe
import io.karandash.providers.common.AdapterHelpers
import io.karandash.sdk.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The pull request with its commits, files, reviews and CI jobs, fetched in parallel. */
internal suspend fun GitHubClient.fetchFullPullRequest(
    owner: String,
    repo: String,
    prNumber: Int,
    logger: Logger,
): MergeRequest =
    coroutineScope {
        val prDeferred = async { fetchPullRequest(owner, repo, prNumber) }
        val commitsDeferred = async { PaginationProbe.probe { fetchCommits(owner, repo, prNumber) } }
        val filesDeferred = async { fetchFiles(owner, repo, prNumber) }
        val reviewsDeferred = async { fetchReviews(owner, repo, prNumber) }
        val pr = prDeferred.await()
        // the pull request's commit list stops at 250; compare pages past it, up to http.pagination.maxPages
        val listed = commitsDeferred.await()
        val commits = if ((pr.commits ?: 0) > listed.first.size) {
            PaginationProbe.probe { fetchCompareCommits(owner, repo, pr.base.sha, pr.head.sha) }
        } else {
            listed
        }
        val ciJobs = fetchCiJobs(owner, repo, pr.head.sha, logger)
        GitHubAdapter
            .toMergeRequest(
                pr,
                commits.first,
                filesDeferred.await(),
                reviewsDeferred.await(),
            ).copy(
                ciStatus = ciJobs?.let { AdapterHelpers.aggregateCiStatus(it) },
                ciJobs = ciJobs.orEmpty(),
                commitsTruncated = commits.second || (pr.commits ?: 0) > commits.first.size,
                commitsTotal = pr.commits,
            )
    }

/** Commit statuses + check runs for [sha]; null (CI status unknown) with a warning when either call fails. */
@Suppress("TooGenericExceptionCaught")
private suspend fun GitHubClient.fetchCiJobs(
    owner: String,
    repo: String,
    sha: String,
    logger: Logger,
): List<CiJobStatus>? =
    try {
        coroutineScope {
            val combined = async { fetchCombinedStatus(owner, repo, sha) }
            val checkRuns = async { fetchCheckRuns(owner, repo, sha) }
            GitHubAdapter.toCiJobs(combined.await(), checkRuns.await())
        }
    } catch (e: Exception) {
        // boundary: CI status is optional MR data; a failed lookup must not fail the fetch
        e.rethrowCancellation()
        logger.warn("GitHub: could not fetch CI status for $owner/$repo@$sha: ${e.message}")
        null
    }
