@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.core

import io.karandash.api.errors.ProviderException
import io.karandash.api.exception.ProviderApiException
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import kotlinx.io.IOException

/**
 * the MR author's track record from the platform's merge-request search, which fills
 * [MrHistory.authorMergeSuccessRate]. Without a searchable platform, or without finished MRs by the author,
 * the history stays neutral; so does a failed search (403, 429, network), with a warning.
 */
object AuthorHistory {
    /** Past MRs by the author read for the rate. */
    const val SEARCH_LIMIT = 20

    /** The author's history from the run's [RunServiceKeys.MERGE_REQUEST_SEARCH]. */
    suspend fun load(
        mr: MergeRequest,
        logger: Logger = NoOpLogger,
    ): MrHistory {
        // limit: one search per caller and run; cache it through CACHE_PROVIDER if the platform rate-limits
        val search = RunScopedServices.current()?.get(RunServiceKeys.MERGE_REQUEST_SEARCH) ?: return MrHistory()
        val hits = try {
            search.search(null, mr.author.username, SEARCH_LIMIT)
        } catch (e: ProviderApiException) {
            return unavailable(e, logger)
        } catch (e: ProviderException) {
            return unavailable(e, logger)
        } catch (e: IOException) {
            return unavailable(e, logger)
        }
        return hits?.let { of(mr, it) } ?: MrHistory()
    }

    /** A failed search never aborts the run: the history stays neutral and the failure is a warning. */
    private fun unavailable(
        e: Exception,
        logger: Logger,
    ): MrHistory {
        e.rethrowCancellation()
        logger.warn("Author history unavailable, author familiarity stays neutral: ${e.message}")
        return MrHistory()
    }

    /** Merged MRs over merged and closed ones among [hits] by the author, [mr] itself excluded. */
    fun of(
        mr: MergeRequest,
        hits: List<MergeRequestSearchHit>,
    ): MrHistory {
        val finished = hits
            .filter { it.id != mr.id && it.id != mr.iid.toString() }
            .filter { it.author == null || it.author == mr.author.username }
            .map { it.state }
            .filter { it == MergeRequestSearchHit.MERGED || it == MergeRequestSearchHit.CLOSED }
        if (finished.isEmpty()) return MrHistory()
        return MrHistory(authorMergeSuccessRate = finished.count { it == MergeRequestSearchHit.MERGED }.toDouble() / finished.size)
    }
}
