package io.karandash.cache.remote

import io.karandash.cache.cas.CasDigest

/**
 * Remote cache backend interface for distributing CAS blobs across machines.
 *
 * Implementations must be safe for concurrent use from coroutines.
 * All operations should be best-effort -- callers handle failures gracefully.
 */
interface RemoteCacheBackend {
    /** Check whether the remote has a blob for the given digest. */
    suspend fun exists(digest: CasDigest): Boolean

    /** Pull blob content from the remote. Returns null if not found. */
    suspend fun pull(digest: CasDigest): ByteArray?

    /**
     * Push blob content to the remote. Idempotent -- same digest overwrites safely.
     *
     * @return true when the remote stored the blob, false when it refused it (the backend logs why).
     */
    suspend fun push(
        digest: CasDigest,
        content: ByteArray
    ): Boolean

    /** Delete a blob from the remote. Best-effort -- failures are logged, not thrown. */
    suspend fun delete(digest: CasDigest)

    /** Release resources held by this backend (e.g. HTTP connections). */
    suspend fun shutdown()
}
