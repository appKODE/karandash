package io.karandash.cacheserver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/**
 * Server counters since start, served by `GET /metrics`.
 *
 * @lifecycle application-scoped -- updated concurrently through [MutableStateFlow.update]
 */
class CacheMetrics {
    val hits = MutableStateFlow(0L)
    val misses = MutableStateFlow(0L)
    val puts = MutableStateFlow(0L)
    val bytesWritten = MutableStateFlow(0L)
    val bytesRead = MutableStateFlow(0L)
    val evictions = MutableStateFlow(0L)
    val rejected = MutableStateFlow(0L)

    /** The counters now, plus the [storedBytes] currently on disk. */
    fun snapshot(storedBytes: Long): MetricsSnapshot =
        MetricsSnapshot(
            hits = hits.value,
            misses = misses.value,
            puts = puts.value,
            bytesWritten = bytesWritten.value,
            bytesRead = bytesRead.value,
            storedBytes = storedBytes,
            evictions = evictions.value,
            rejected = rejected.value,
        )
}

/** `GET /metrics` body. [rejected] counts PUTs refused for a digest mismatch or an oversized body. */
@Serializable
data class MetricsSnapshot(
    val hits: Long,
    val misses: Long,
    val puts: Long,
    val bytesWritten: Long,
    val bytesRead: Long,
    val storedBytes: Long,
    val evictions: Long,
    val rejected: Long,
)
