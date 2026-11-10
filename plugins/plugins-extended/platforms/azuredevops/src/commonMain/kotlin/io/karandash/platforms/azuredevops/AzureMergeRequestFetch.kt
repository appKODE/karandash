package io.karandash.platforms.azuredevops

import io.karandash.api.support.rethrowCancellation
import io.karandash.models.CiJobStatus
import io.karandash.models.MergeRequest
import io.karandash.pagination.PaginationProbe
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.UnifiedDiff
import io.karandash.sdk.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The pull request with its commits, diffs (from blobs) and CI jobs, fetched in parallel. */
internal suspend fun AzureDevOpsProviderClient.fetchFullPullRequest(
    organization: String,
    project: String,
    repository: String,
    prId: String,
    logger: Logger,
): MergeRequest =
    coroutineScope {
        val prDeferred = async { fetchPullRequest(organization, project, repository, prId) }
        val commitsDeferred = async { PaginationProbe.probe { fetchCommits(organization, project, repository, prId) } }
        val changesDeferred = async { fetchChanges(organization, project, repository, prId) }
        val ciJobsDeferred = async { fetchCiJobs(organization, project, repository, prId, logger) }
        val changes = changesDeferred.await()
        val mr = AzureDevOpsAdapter.toMergeRequest(prDeferred.await(), commitsDeferred.await().first, changes)
        val items = changes.mapNotNull { entry -> entry.item?.let { it to entry.changeType.contains("delete", ignoreCase = true) } }
        val diffs = UnifiedDiff.fromContents(items, { (item, _) -> item.path.removePrefix("/") }) { (item, deleted) ->
            // A missing side is an empty file; a blob that fails to load skips the file rather than faking a diff.
            val blob: suspend (String?) -> String? = { id ->
                if (id == null) "" else fetchBlob(organization, project, repository, id)
            }
            val before = blob(item.originalObjectId)
            val after = if (deleted) "" else blob(item.objectId)
            if (before != null && after != null) before to after else null
        }
        val ciJobs = ciJobsDeferred.await()
        mr.copy(
            changes = UnifiedDiff.attach(mr.changes, diffs),
            ciStatus = ciJobs?.let { AdapterHelpers.aggregateCiStatus(it) },
            ciJobs = ciJobs.orEmpty(),
            commitsTruncated = commitsDeferred.await().second,
        )
    }

/** PR statuses as CI jobs, the latest per context; null with a warning when the lookup fails. */
@Suppress("TooGenericExceptionCaught")
private suspend fun AzureDevOpsProviderClient.fetchCiJobs(
    organization: String,
    project: String,
    repository: String,
    prId: String,
    logger: Logger,
): List<CiJobStatus>? =
    try {
        AzureDevOpsAdapter.toCiJobs(fetchStatuses(organization, project, repository, prId))
    } catch (e: Exception) {
        // boundary: CI status is optional MR data; a failed lookup must not fail the fetch
        e.rethrowCancellation()
        logger.warn("Azure DevOps: could not fetch PR statuses for $repository#$prId: ${e.message}")
        null
    }
