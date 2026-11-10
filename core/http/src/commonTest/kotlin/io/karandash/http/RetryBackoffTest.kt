package io.karandash.http

import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryBackoffTest {
    @Test
    fun `the backoff doubles from the base delay`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L), (1..3).map { RetryBackoff.delayMs(it, 1_000L) })
    }

    @Test
    fun `a late attempt is capped at the longest wait instead of overflowing`() {
        assertEquals(RetryBackoff.MAX_DELAY_MS, RetryBackoff.delayMs(40, 1_000L))
        assertEquals(RetryBackoff.MAX_DELAY_MS, RetryBackoff.delayMs(64, 1_000L))
    }

    @Test
    fun `a server wait longer than the backoff wins and is capped too`() {
        assertEquals(7_000L, RetryBackoff.delayMs(1, 1_000L, retryAfterMs = 7_000L))
        assertEquals(RetryBackoff.MAX_DELAY_MS, RetryBackoff.delayMs(1, 1_000L, retryAfterMs = 3_600_000L))
        assertEquals(4_000L, RetryBackoff.delayMs(3, 1_000L, retryAfterMs = 10L))
    }

    @Test
    fun `Retry-After in seconds and retry-after-ms in milliseconds read the same`() {
        assertEquals(2_000L, RetryBackoff.retryAfterMs(headersOf("Retry-After", "2")))
        assertEquals(2_000L, RetryBackoff.retryAfterMs(headersOf("retry-after-ms", "2000")))
    }

    @Test
    fun `an HTTP-date Retry-After or none falls back to the backoff`() {
        assertNull(RetryBackoff.retryAfterMs(headersOf("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT")))
        assertNull(RetryBackoff.retryAfterMs(headersOf()))
    }
}
