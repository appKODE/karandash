package io.karandash.http

import io.karandash.duration.KarandashDuration
import kotlinx.serialization.Serializable

/**
 * Run-wide settings for clients built by [ProviderHttpClientFactory], taken from the `http:` and
 * `timeouts:` config blocks. A null field keeps the built-in behavior.
 *
 * @param timeout Default request/connect/socket timeout (`timeouts.http`); an explicit `timeout` argument wins.
 * @param retry 5xx and 429 retry for idempotent requests (`http.retry`); null = [Retry.DEFAULT].
 * @param maxPages Pagination safety cap (`http.pagination.maxPages`); null = each client's own cap.
 */
@Serializable
data class HttpClientSettings(
    val timeout: KarandashDuration? = null,
    val retry: Retry? = null,
    val maxPages: Int? = null,
) {
    /**
     * @param maxRetries Retries after the first attempt.
     * @param baseDelay Base delay of the exponential backoff.
     */
    @Serializable
    data class Retry(
        val maxRetries: Int,
        val baseDelay: KarandashDuration,
    ) {
        companion object {
            /** Used when `http.retry` is unset: two retries, 1s base delay. */
            val DEFAULT = Retry(maxRetries = 2, baseDelay = KarandashDuration.ofSeconds(1))
        }
    }
}
