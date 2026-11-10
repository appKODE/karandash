@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.run.RepositoryFileReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [RepositoryFileReader] that fetches each `ref`/`path` once per run, a miss included, so every AI rule resolving
 * the same imports shares one platform call; concurrent reads of one file wait for the first.
 *
 * limit: unbounded, but per run and bounded by the rules' lookup caps.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
internal class MemoizedRepositoryFileReader(
    private val delegate: RepositoryFileReader,
) : RepositoryFileReader {
    private val mutex = Mutex()
    private var reads: Map<String, CompletableDeferred<String?>> = emptyMap()

    override suspend fun read(
        ref: String,
        path: String,
    ): String? {
        val key = "$ref\u0000$path"
        val fresh = CompletableDeferred<String?>()
        val slot = mutex.withLock { reads[key] ?: fresh.also { reads = reads + (key to it) } }
        if (slot !== fresh) return slot.await()
        try {
            return delegate.read(ref, path).also { slot.complete(it) }
        } finally {
            // A failed or cancelled fetch reads as "cannot fetch" for the waiters, as the reader contract allows.
            slot.complete(null)
        }
    }
}
