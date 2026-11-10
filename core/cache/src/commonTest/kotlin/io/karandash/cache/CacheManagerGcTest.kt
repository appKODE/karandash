package io.karandash.cache

import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CacheManagerGcTest {
    private lateinit var tempDir: Path
    private lateinit var manager: CacheManager

    @BeforeTest
    fun setUp() {
        tempDir = TestFiles.createTestTempDirectory("karandash-gc-mgr-test")
        SystemFileSystem.createDirectories(tempDir)
        manager = CacheManager(config = CacheConfig(), cacheDir = tempDir)
    }

    @AfterTest
    fun tearDown() {
        TestFiles.deleteRecursively(tempDir)
    }

    @Test
    fun runGcReturnsZeroOnEmptyCache() {
        manager.getCasCache("state")
        val result = manager.runGc()
        assertEquals(0, result.expiredIndexesRemoved)
        assertEquals(0, result.orphanBlobsRemoved)
        assertEquals(0L, result.bytesReclaimed)
    }

    @Test
    fun runGcRemovesExpiredEntries() {
        val cache = manager.getCasCache("state")
        // Write with negative TTL - already expired
        cache.put("key1", "content1".encodeToByteArray(), ttlMs = -1_000L)
        cache.put("key2", "content2".encodeToByteArray(), ttlMs = -1_000L)

        val result = manager.runGc()
        assertEquals(2, result.expiredIndexesRemoved)
        assertTrue(result.bytesReclaimed > 0)
    }

    @Test
    fun runGcRemovesOrphanBlobs() {
        val cache = manager.getCasCache("state")
        // Write an entry then invalidate the index - blob becomes orphan
        cache.put("key1", "orphan-content".encodeToByteArray(), ttlMs = 3_600_000L)
        cache.invalidate("key1")

        val result = manager.runGc()
        assertEquals(1, result.orphanBlobsRemoved)
        assertTrue(result.bytesReclaimed > 0)
    }

    @Test
    fun runGcKeepsBlobsOfANamespaceThisRunDidNotUse() {
        // the config pipeline caches `extends:` downloads through its own manager; this run only used `state`
        val downloads = CacheManager(config = CacheConfig(), cacheDir = tempDir).getCasCache("downloads")
        downloads.put("extends-x", "remote-config".encodeToByteArray(), ttlMs = 3_600_000L)
        manager.getCasCache("state")

        val result = manager.runGc()

        assertEquals(0, result.orphanBlobsRemoved)
        assertEquals("remote-config", downloads.get("extends-x")?.decodeToString())
    }

    @Test
    fun runGcPreservesLiveEntries() {
        val cache = manager.getCasCache("state")
        val content = "live-content".encodeToByteArray()
        cache.put("live-key", content, ttlMs = 3_600_000L)

        val result = manager.runGc()
        assertEquals(0, result.expiredIndexesRemoved)
        assertEquals(0, result.orphanBlobsRemoved)

        // Verify the entry is still readable
        val read = cache.get("live-key")
        assertTrue(read != null)
        assertEquals("live-content", read.decodeToString())
    }

    @Test
    fun runGcHandlesMultipleNamespaces() {
        val stateCache = manager.getCasCache("state")
        val downloadsCache = manager.getCasCache("downloads")

        stateCache.put("s1", "state-data".encodeToByteArray(), ttlMs = -1_000L)
        downloadsCache.put("d1", "download-data".encodeToByteArray(), ttlMs = -1_000L)

        val result = manager.runGc()
        assertEquals(2, result.expiredIndexesRemoved)
    }

    @Test
    fun runGcEvictsOldestEntriesOverMaxCacheSize() {
        val bounded = CacheManager(config = CacheConfig(maxCacheSize = 10L), cacheDir = tempDir)
        val cache = bounded.getCasCache("state")
        cache.put("k1", "content1".encodeToByteArray(), ttlMs = 3_600_000L)
        cache.put("k2", "content2".encodeToByteArray(), ttlMs = 3_600_000L)
        cache.put("k3", "content3".encodeToByteArray(), ttlMs = 3_600_000L)

        val result = bounded.runGc()

        assertEquals(2, result.expiredIndexesRemoved)
        assertEquals(16L, result.bytesReclaimed)
        assertEquals(1, cache.keys().size)
    }

    @Test
    fun runGcKeepsEverythingWithoutMaxCacheSize() {
        val cache = manager.getCasCache("state")
        cache.put("k1", "content1".encodeToByteArray(), ttlMs = 3_600_000L)
        cache.put("k2", "content2".encodeToByteArray(), ttlMs = 3_600_000L)

        manager.runGc()

        assertEquals(2, cache.keys().size)
    }
}
