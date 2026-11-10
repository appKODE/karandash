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

class CasCacheTest {
    private lateinit var tempDir: Path
    private lateinit var cache: CasCache

    @BeforeTest
    fun setUp() {
        tempDir = TestFiles.createTestTempDirectory("karandash-cas-cache-test")
        SystemFileSystem.createDirectories(tempDir)
        cache = CasCache(baseDir = tempDir, namespace = "test")
    }

    @AfterTest
    fun tearDown() {
        TestFiles.deleteRecursively(tempDir)
    }

    @Test
    fun getReturnsNullOnMiss() {
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun getReturnsNullForExpiredEntry() {
        val content = "expired".encodeToByteArray()
        // TTL of 1ms - already expired by the time we read
        cache.put("key", content, ttlMs = 1L)
        // Busy-wait is not reliable; use a TTL in the past by setting a negative delta
        // Instead, write directly with expired timestamp via put with ttl=0 (expires immediately)
        // The safest approach: put with ttlMs = -1000 so expiresAt is in the past
        cache.put("expired-key", content, ttlMs = -1_000L)
        assertNull(cache.get("expired-key"))
    }

    @Test
    fun putAndGetRoundTripsBytes() {
        val content = "hello cache".encodeToByteArray()
        cache.put("k", content, ttlMs = 60_000L)
        val retrieved = cache.get("k")
        assertNotNull(retrieved)
        assertEquals("hello cache", retrieved.decodeToString())
    }

    @Test
    fun getOrComputeCallsComputeOnMiss() {
        var computed = false
        val result = cache.getOrCompute("miss-key", ttlMs = 60_000L) {
            computed = true
            "computed-value".encodeToByteArray()
        }
        assertEquals("computed-value", result.decodeToString())
        assertEquals(true, computed)
    }

    @Test
    fun getOrComputeDoesNotCallComputeOnHit() {
        cache.put("hit-key", "cached".encodeToByteArray(), ttlMs = 60_000L)
        var computed = false
        val result = cache.getOrCompute("hit-key", ttlMs = 60_000L) {
            computed = true
            "fresh".encodeToByteArray()
        }
        assertEquals("cached", result.decodeToString())
        assertEquals(false, computed)
    }

    @Test
    fun refreshTtlExtendsExpiry() {
        val content = "refresh-me".encodeToByteArray()
        cache.put("r", content, ttlMs = 60_000L)
        val before = cache.getIndex("r")
        assertNotNull(before)

        cache.refreshTtl("r", ttlMs = 120_000L)
        val after = cache.getIndex("r")
        assertNotNull(after)
        assertTrue(after.expiresAt > before.expiresAt)
    }

    @Test
    fun invalidateRemovesEntry() {
        cache.put("del", "value".encodeToByteArray(), ttlMs = 60_000L)
        assertNotNull(cache.get("del"))
        cache.invalidate("del")
        assertNull(cache.get("del"))
    }

    @Test
    fun keysReturnsAllStoredKeys() {
        cache.put("a", "1".encodeToByteArray(), ttlMs = 60_000L)
        cache.put("b", "2".encodeToByteArray(), ttlMs = 60_000L)
        val keys = cache.keys()
        assertEquals(2, keys.size)
        assertTrue(keys.contains("a"))
        assertTrue(keys.contains("b"))
    }

    @Test
    fun getRemovesAnIndexEntryWhoseBlobIsGone() {
        cache.put("key", "bytes".encodeToByteArray(), ttlMs = 600_000L)
        val digest = checkNotNull(cache.getIndex("key")).casDigest()
        CasBlobStore(Path(tempDir, "cas")).delete(digest)

        assertNull(cache.get("key"))
        assertNull(cache.getIndex("key"))
    }
}
