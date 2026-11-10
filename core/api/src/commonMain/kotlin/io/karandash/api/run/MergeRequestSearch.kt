package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.platform.MergeRequestSearchHit

/**
 * Searches the run project's merge requests through the run's platform service
 * (`PlatformServiceModule.searchMergeRequests`). Provided as [RunServiceKeys.MERGE_REQUEST_SEARCH] only when the run
 * has a live platform and project.
 */
@ExperimentalKarandashApi
public fun interface MergeRequestSearch {
    /** At most [limit] MRs matching [text] and/or by [author], newest first; `null` when the platform cannot search. */
    public suspend fun search(
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit>?
}
