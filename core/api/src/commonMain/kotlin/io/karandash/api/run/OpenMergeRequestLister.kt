package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.models.MergeRequest

/**
 * Fetches the open merge requests of the run's project that target a branch, through the run's platform service
 * (`PlatformServiceModule.listOpenMergeRequestIds` + `fetchMergeRequest`). Provided as
 * [RunServiceKeys.OPEN_MERGE_REQUESTS] only when the run has a live platform and project.
 */
@ExperimentalKarandashApi
public fun interface OpenMergeRequestLister {
    /** At most [limit] open MRs targeting [targetBranch]; `null` when the platform cannot list them. */
    public suspend fun list(
        targetBranch: String,
        limit: Int,
    ): List<MergeRequest>?
}
