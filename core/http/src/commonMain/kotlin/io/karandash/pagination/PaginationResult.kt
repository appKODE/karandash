package io.karandash.pagination

import kotlinx.serialization.Serializable

/**
 * Result of a single paginated page fetch.
 *
 * @param T The item type in the response.
 * @param items Items returned on this page.
 * @param nextPageInfo Opaque info for fetching the next page, or `null` if this is the last page.
 */
@Serializable
data class PaginationResult<T>(
    val items: List<T>,
    val nextPageInfo: String?,
)
