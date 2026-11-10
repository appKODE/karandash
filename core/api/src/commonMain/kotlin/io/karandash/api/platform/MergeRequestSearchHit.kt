package io.karandash.api.platform

import io.karandash.api.ExperimentalKarandashApi
import kotlinx.serialization.Serializable

/**
 * One merge request returned by
 * [PlatformServiceModule.searchMergeRequests][io.karandash.api.module.PlatformServiceModule.searchMergeRequests].
 *
 * @param id the id [io.karandash.api.module.PlatformServiceModule.fetchMergeRequest] accepts (GitLab iid, GitHub number).
 * @param state [MERGED], [CLOSED] or [OPENED].
 */
@ExperimentalKarandashApi
@Serializable
public data class MergeRequestSearchHit(
    val id: String,
    val title: String,
    val state: String,
    val author: String? = null,
    val webUrl: String? = null,
) {
    public companion object {
        public const val MERGED: String = "merged"
        public const val CLOSED: String = "closed"
        public const val OPENED: String = "opened"
    }
}
