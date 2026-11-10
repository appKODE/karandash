package io.karandash.pagination

import io.karandash.http.ProviderHttpClientFactory

/**
 * Collects all pages from a paginated API using page-number-based pagination.
 *
 * Used by GitLab (`?page=N&per_page=N`, `x-next-page` header) and
 * Gitea (`?page=N&limit=N`, response size < limit).
 *
 * @param maxPages Safety cap on total pages fetched; null: the run's `http.pagination.maxPages`, else 10.
 * @param onTruncated Called with the cap when it stops the loop while a next page is still pending.
 * @param fetchPage Fetches a single page by number. Returns items and optional next page number string.
 * @return Accumulated list of all items across all pages.
 */
suspend fun <T> paginateByPageNumber(
    maxPages: Int? = null,
    onTruncated: (cap: Int) -> Unit = {},
    fetchPage: suspend (page: Int) -> PaginationResult<T>,
): List<T> {
    val cap = maxPages ?: ProviderHttpClientFactory.maxPages(DEFAULT_MAX_PAGES)
    val allItems = mutableListOf<T>()
    var page: Int? = 1
    var fetched = 0
    while (page != null && fetched < cap) {
        val result = fetchPage(page)
        allItems.addAll(result.items)
        fetched++
        page = result.nextPageInfo?.toIntOrNull()
    }
    if (page != null) {
        onTruncated(cap)
        PaginationProbe.markTruncated()
    }
    return allItems
}

/**
 * Collects all pages from a paginated API using URL-based continuation.
 *
 * Used by GitHub (`Link: <url>; rel="next"` header) and
 * Bitbucket (`next` field in JSON response body).
 *
 * @param firstUrl The initial URL to fetch.
 * @param maxPages Safety cap on total pages fetched; null: the run's `http.pagination.maxPages`, else 10.
 * @param onTruncated Called with the cap when it stops the loop while a next URL is still pending.
 * @param fetchPage Fetches a single page by URL. Returns items and optional next URL.
 * @return Accumulated list of all items across all pages.
 */
suspend fun <T> paginateByUrl(
    firstUrl: String,
    maxPages: Int? = null,
    onTruncated: (cap: Int) -> Unit = {},
    fetchPage: suspend (url: String) -> PaginationResult<T>,
): List<T> = paginateByCursor(maxPages, onTruncated) { fetchPage(it ?: firstUrl) }

/**
 * Collects all pages from a paginated API using an opaque cursor: the first page is fetched with a null cursor,
 * each later one with the [PaginationResult.nextPageInfo] of the page before.
 *
 * Used by GitHub GraphQL (`endCursor`), AWS CodeCommit (`nextToken`), Gerrit (`_more_changes` offset) and Azure
 * DevOps (`x-ms-continuationtoken`).
 *
 * @param maxPages Safety cap on total pages fetched; null: the run's `http.pagination.maxPages`, else 10.
 * @param onTruncated Called with the cap when it stops the loop while a next cursor is still pending.
 * @param fetchPage Fetches a single page by cursor (null for the first). Returns items and the next cursor, if any.
 * @return Accumulated list of all items across all pages.
 */
suspend fun <T> paginateByCursor(
    maxPages: Int? = null,
    onTruncated: (cap: Int) -> Unit = {},
    fetchPage: suspend (cursor: String?) -> PaginationResult<T>,
): List<T> {
    val cap = maxPages ?: ProviderHttpClientFactory.maxPages(DEFAULT_MAX_PAGES)
    val allItems = mutableListOf<T>()
    var cursor: String? = null
    var pagesFetched = 0
    var more = true
    while (more && pagesFetched < cap) {
        val result = fetchPage(cursor)
        allItems.addAll(result.items)
        pagesFetched++
        cursor = result.nextPageInfo
        more = cursor != null
    }
    if (more) {
        onTruncated(cap)
        PaginationProbe.markTruncated()
    }
    return allItems
}

/**
 * Parses the `next` URL from a GitHub-style RFC 5988 `Link` response header.
 *
 * Format: `<url>; rel="next", <url>; rel="last"`
 *
 * @param linkHeader The raw `Link` header value, or null.
 * @return The URL for the next page, or null if not present.
 */
fun parseLinkHeaderNext(linkHeader: String?): String? {
    if (linkHeader == null) return null
    return linkHeader.split(",").firstNotNullOfOrNull { part ->
        val segments = part.trim().split(";")
        if (segments.size >= 2 && segments[1].trim() == """rel="next"""") {
            segments[0]
                .trim()
                .removePrefix("<")
                .removeSuffix(">")
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }
}

/** Default safety cap on pagination. */
const val DEFAULT_MAX_PAGES = 10
