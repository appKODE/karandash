package io.karandash.cache.cas

import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheGarbageCollectorTest {
    private lateinit var tempDir: Path
    private lateinit var cache: CasCache
    private lateinit var gc: CacheGarbageCollector

    @BeforeTest
    fun setUp() {
        tempDir = TestFiles.createTestTempDirectory("karandash-gc-test")
        SystemFileSystem.createDirectories(tempDir)
        cache = CasCache(baseDir = tempDir, namespace = "test")
        gc = CacheGarbageCollector(baseDir = tempDir)
    }

    @AfterTest
    fun tearDown() {
        TestFiles.deleteRecursively(tempDir)
    }

    @Test
    fun collectExpiredRemovesExpiredEntries() {
        // Put an entry that is already expired (negative TTL)
        cache.put("expired", "data".encodeToByteArray(), ttlMs = -1_000L)
        // Put a live entry
        cache.put("live", "alive".encodeToByteArray(), ttlMs = 600_000L)

        val result = gc.collectExpired(listOf("test"))

        assertEquals(1, result.expiredIndexesRemoved)
        assertNull(cache.get("expired"))
        assertNotNull(cache.get("live"))
    }

    @Test
    fun collectExpiredReturnsZeroWhenNothingExpired() {
        cache.put("a", "data".encodeToByteArray(), ttlMs = 600_000L)

        val result = gc.collectExpired(listOf("test"))

        assertEquals(0, result.expiredIndexesRemoved)
    }

    @Test
    fun collectExpiredHandlesEmptyNamespaces() {
        val result = gc.collectExpired(listOf("nonexistent"))

        assertEquals(0, result.expiredIndexesRemoved)
        assertEquals(0, result.orphanBlobsRemoved)
        assertEquals(0L, result.bytesReclaimed)
    }

    @Test
    fun collectOrphansRemovesUnreferencedBlobs() {
        // Put an entry, then delete the index (leaving the blob orphaned)
        cache.put("orphan-key", "orphan-data".encodeToByteArray(), ttlMs = 600_000L)
        cache.invalidate("orphan-key")

        // Put a live entry that should NOT be collected
        cache.put("live-key", "live-data".encodeToByteArray(), ttlMs = 600_000L)

        val result = gc.collectOrphans(listOf("test"))

        assertTrue(result.orphanBlobsRemoved >= 1)
        assertEquals(0, result.expiredIndexesRemoved)
        // Live entry still accessible
        assertNotNull(cache.get("live-key"))
    }

    @Test
    fun collectOrphansReturnsZeroWhenAllBlobsReferenced() {
        cache.put("a", "data-a".encodeToByteArray(), ttlMs = 600_000L)
        cache.put("b", "data-b".encodeToByteArray(), ttlMs = 600_000L)

        val result = gc.collectOrphans(listOf("test"))

        assertEquals(0, result.orphanBlobsRemoved)
    }

    @Test
    fun collectOrphansHandlesEmptyCache() {
        val result = gc.collectOrphans(listOf("test"))

        assertEquals(0, result.orphanBlobsRemoved)
        assertEquals(0L, result.bytesReclaimed)
    }

    @Test
    fun collectExpiredCleansUpOrphanedBlobsFromExpiredEntries() {
        // Put entries that are already expired
        cache.put("e1", "expired-1".encodeToByteArray(), ttlMs = -1_000L)
        cache.put("e2", "expired-2".encodeToByteArray(), ttlMs = -1_000L)

        val result = gc.collectExpired(listOf("test"))

        assertEquals(2, result.expiredIndexesRemoved)
        assertTrue(result.orphanBlobsRemoved >= 2)
    }

    @Test
    fun evictOverSizeKeepsBlobsReferencedByOtherNamespaces() {
        val downloads = CasCache(baseDir = tempDir, namespace = "downloads")
        downloads.put("shared", "same-bytes".encodeToByteArray(), ttlMs = 600_000L)
        cache.put("shared", "same-bytes".encodeToByteArray(), ttlMs = 600_000L)

        val result = gc.evictOverSize(listOf("test"), maxBytes = 1)

        assertEquals(1, result.expiredIndexesRemoved)
        assertEquals(0, result.orphanBlobsRemoved)
        assertNull(cache.get("shared"))
        assertEquals("same-bytes", downloads.get("shared")?.decodeToString())
    }

    @Test
    fun evictOverSizeRemovesOldestFirst() {
        val index = CacheIndexStore(Path(tempDir, "index"), SystemFileSystem)
        val blobs = CasBlobStore(Path(tempDir, "cas"), SystemFileSystem)
        val far = Long.MAX_VALUE / 2
        index.write("test", "old", CacheIndexEntry(digest = blobs.put("old-bytes".encodeToByteArray()).hex, expiresAt = far, createdAt = 1))
        index.write("test", "new", CacheIndexEntry(digest = blobs.put("new-bytes".encodeToByteArray()).hex, expiresAt = far, createdAt = 2))

        gc.evictOverSize(listOf("test"), maxBytes = "new-bytes".length.toLong())

        assertNull(cache.get("old"))
        assertEquals("new-bytes", cache.get("new")?.decodeToString())
    }
}
