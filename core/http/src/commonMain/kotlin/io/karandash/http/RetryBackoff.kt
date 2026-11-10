package io.karandash.http

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/**
 * The one retry wait of the HTTP client ([ProviderHttpClientFactory]) and the AI providers' own loop: an exponential
 * backoff from a base delay, raised to a server-requested wait, both capped at [MAX_DELAY_MS].
 */
object RetryBackoff {
    /** Longest wait before a retry, whether from the backoff or from the server. */
    const val MAX_DELAY_MS = 60_000L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MAX_SHIFT = 16

    /**
     * Wait before retry [attempt] (1 for the first retry): `baseDelayMs * 2^(attempt-1)`, at least [retryAfterMs],
     * at most [MAX_DELAY_MS].
     */
    fun delayMs(
        attempt: Int,
        baseDelayMs: Long,
        retryAfterMs: Long? = null,
    ): Long {
        val backoff = (baseDelayMs shl (attempt - 1).coerceIn(0, MAX_SHIFT)).coerceIn(0, MAX_DELAY_MS)
        return maxOf(backoff, retryAfterMs?.coerceIn(0, MAX_DELAY_MS) ?: 0)
    }

    /** `retry-after-ms` (OpenAI) or `Retry-After` in seconds; null when absent or an HTTP-date. */
    fun retryAfterMs(headers: Headers): Long? =
        headers["retry-after-ms"]?.trim()?.toLongOrNull()
            ?: headers[HttpHeaders.RetryAfter]
                ?.trim()
                ?.toLongOrNull()
                ?.coerceIn(0, MAX_DELAY_MS / MILLIS_PER_SECOND)
                ?.times(MILLIS_PER_SECOND)
}
