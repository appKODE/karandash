package io.karandash.config

import io.karandash.duration.KarandashDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * User-facing HTTP client configuration parsed from the `http:` config block.
 *
 * Controls retry behavior, pagination safety caps, and response cache sizing
 * for all outbound HTTP requests made by platform service modules.
 *
 * ```yaml
 * http:
 *   retry:
 *     max-attempts: 2
 *     base-delay: "1s"
 *   pagination:
 *     max-pages: 10
 *   response-cache:
 *     max-entries: 128
 * ```
 *
 * Each block is null when unset, which keeps the built-in behavior: 2 retries with a 1s base delay
 * (`max-attempts: 0` turns retry off), the client's own page cap
 * (10, Gitea 20) and the `cache` block's response cache size.
 */
@Serializable
data class HttpConfig(
    /** Retry for 5xx and 429 answers to idempotent requests (GET/HEAD/PUT/DELETE/OPTIONS); null = 2 retries, 1s base. */
    val retry: HttpRetryConfig? = null,
    /** Pagination safety limits; null = each client's built-in cap. */
    val pagination: HttpPaginationConfig? = null,
    /** HTTP response cache sizing; null = the `cache` block's memory size for `responses`. */
    @SerialName("responseCache")
    val responseCache: HttpResponseCacheConfig? = null,
) {
    /** One warning per field whose configured value is outside its bounds and was clamped. */
    fun boundsWarnings(): List<String> =
        listOfNotNull(
            retry?.let { clampWarning("http.retry.maxAttempts", it.maxAttempts, it.effectiveMaxAttempts) },
            // maxPages <= 0 means "every page", not a clamp
            pagination?.takeIf { it.maxPages > 0 }?.let {
                clampWarning("http.pagination.maxPages", it.maxPages, it.effectiveMaxPages)
            },
        )
}

/**
 * Retry configuration for HTTP requests.
 *
 * Controls exponential backoff behavior for retryable failures (5xx, 429 with its capped `Retry-After`).
 */
@Serializable
data class HttpRetryConfig(
    /** Retries after the first attempt. Coerced to `[0, 10]`. */
    @SerialName("maxAttempts")
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** Base delay for exponential backoff between retries. */
    @SerialName("baseDelay")
    val baseDelay: KarandashDuration = DEFAULT_BASE_DELAY,
) {
    /** Effective max attempts after applying safe bounds. */
    val effectiveMaxAttempts: Int get() = maxAttempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS_BOUND)

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 2
        val DEFAULT_BASE_DELAY: KarandashDuration = KarandashDuration.ofSeconds(1)
        private const val MIN_ATTEMPTS = 0
        private const val MAX_ATTEMPTS_BOUND = 10
    }
}

/**
 * Pagination safety limits for paginated API responses.
 */
@Serializable
data class HttpPaginationConfig(
    /** Maximum number of pages to fetch. Coerced to `[1, 100]`; `0` (or less) reads every page. */
    @SerialName("maxPages")
    val maxPages: Int = DEFAULT_MAX_PAGES,
) {
    /** Effective max pages after applying safe bounds; [Int.MAX_VALUE] when [maxPages] asks for every page. */
    val effectiveMaxPages: Int get() = if (maxPages <= 0) Int.MAX_VALUE else maxPages.coerceIn(MIN_PAGES, MAX_PAGES_BOUND)

    companion object {
        const val DEFAULT_MAX_PAGES = 10
        private const val MIN_PAGES = 1
        private const val MAX_PAGES_BOUND = 100
    }
}

/**
 * Configuration for the in-memory HTTP response cache.
 */
@Serializable
data class HttpResponseCacheConfig(
    /** Maximum number of cached HTTP responses. */
    @SerialName("maxEntries")
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 128
    }
}
