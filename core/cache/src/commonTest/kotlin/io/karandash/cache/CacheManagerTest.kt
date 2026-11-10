package io.karandash.cache

import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class CacheManagerTest {
    private fun createManager(config: CacheConfig = CacheConfig()): CacheManager =
        CacheManager(
            config = config,
            cacheDir = TestFiles.createTestTempDirectory("karandash-test-cache"),
        )

    @Test
    fun getOrCreateReturnsSameInstanceForSameName() {
        val manager = createManager()
        val spec = CacheSpec<String, String>(maxEntries = 100)

        val cache1 = manager.getOrCreate("test", spec)
        val cache2 = manager.getOrCreate("test", spec)

        assertSame(cache1, cache2)
    }

    @Test
    fun getOrCreateReturnsDifferentInstancesForDifferentNames() {
        val manager = createManager()
        val spec = CacheSpec<String, String>(maxEntries = 100)

        val cache1 = manager.getOrCreate("cache-a", spec)
        val cache2 = manager.getOrCreate("cache-b", spec)

        assertEquals("cache-a", cache1.name)
        assertEquals("cache-b", cache2.name)
    }

    @Test
    fun statsReturnsAllCacheStats() {
        val manager = createManager()
        val spec = CacheSpec<String, String>(maxEntries = 10)

        val cache1 = manager.getOrCreate("a", spec)
        val cache2 = manager.getOrCreate("b", spec)
        cache1.put("k1", "v1")
        cache2.put("k2", "v2")

        val stats = manager.stats()
        assertEquals(2, stats.size)
        assertNotNull(stats["a"])
        assertNotNull(stats["b"])
        assertEquals(1, stats["a"]!!.size)
        assertEquals(1, stats["b"]!!.size)
    }

    @Test
    fun statsForNameReturnsNullForUnknownCache() {
        val manager = createManager()
        assertNull(manager.stats("nonexistent"))
    }

    @Test
    fun statsForNameReturnsStats() =
        runTest {
            val manager = createManager()
            val cache = manager.getOrCreate("test", CacheSpec<String, String>(maxEntries = 10))
            cache.put("k", "v")
            cache.get("k")

            val stats = manager.stats("test")
            assertNotNull(stats)
            assertEquals(1L, stats.hits)
            assertEquals(1, stats.size)
        }

    @Test
    fun aggregateStatsCombinesAll() =
        runTest {
            val manager = createManager()
            val spec = CacheSpec<String, String>(maxEntries = 10)

            val c1 = manager.getOrCreate("a", spec)
            val c2 = manager.getOrCreate("b", spec)
            c1.put("k1", "v1")
            c2.put("k2", "v2")
            c1.get("k1") // hit
            c2.get("missing") // miss

            val agg = manager.aggregateStats()
            assertEquals(2, agg.perCache.size)
            assertEquals(1L, agg.totalHits)
            assertEquals(1L, agg.totalMisses)
            assertEquals(2, agg.totalSize)
        }

    @Test
    fun invalidateAllClearsAllCaches() =
        runTest {
            val manager = createManager()
            val spec = CacheSpec<String, String>(maxEntries = 10)

            val c1 = manager.getOrCreate("a", spec)
            val c2 = manager.getOrCreate("b", spec)
            c1.put("k1", "v1")
            c2.put("k2", "v2")

            manager.invalidateAll()
            assertNull(c1.get("k1"))
            assertNull(c2.get("k2"))
        }

    @Test
    fun invalidateByNameClearsOnlyTargetCache() =
        runTest {
            val manager = createManager()
            val spec = CacheSpec<String, String>(maxEntries = 10)

            val c1 = manager.getOrCreate("a", spec)
            val c2 = manager.getOrCreate("b", spec)
            c1.put("k1", "v1")
            c2.put("k2", "v2")

            manager.invalidate("a")
            assertNull(c1.get("k1"))
            assertEquals("v2", c2.get("k2"))
        }

    @Test
    fun invalidateUnknownNameIsNoOp() {
        val manager = createManager()
        manager.invalidate("nonexistent") // should not throw
    }

    @Test
    fun shutdownClearsRegistry() {
        val manager = createManager()
        val spec = CacheSpec<String, String>(maxEntries = 10)
        manager.getOrCreate("test", spec)

        manager.shutdown()
        assertEquals(0, manager.stats().size)
    }

    @Test
    fun memoryMaxEntriesReturnsPerCacheOverride() {
        val config = CacheConfig(
            memory = MemoryCacheConfig(
                maxEntries = 512,
                overrides = mapOf(
                    "glob-patterns" to MemoryOverride(maxEntries = 2000),
                    "responses" to MemoryOverride(maxEntries = 128),
                ),
            ),
        )
        val manager = createManager(config)

        assertEquals(2000, manager.memoryMaxEntries("glob-patterns"))
        assertEquals(128, manager.memoryMaxEntries("responses"))
    }

    @Test
    fun memoryMaxEntriesFallsBackToGlobalDefault() {
        val config = CacheConfig(
            memory = MemoryCacheConfig(maxEntries = 256),
        )
        val manager = createManager(config)

        assertEquals(256, manager.memoryMaxEntries("unknown-cache"))
        assertEquals(256, manager.memoryMaxEntries("downloads"))
        assertEquals(256, manager.memoryMaxEntries("state"))
    }

    @Test
    fun memoryMaxEntriesUsedForCacheCreation() {
        val config = CacheConfig(
            memory = MemoryCacheConfig(
                maxEntries = 512,
                overrides = mapOf("responses" to MemoryOverride(maxEntries = 5)),
            ),
        )
        val manager = createManager(config)
        val cache = manager.getOrCreate(
            "responses",
            CacheSpec<String, String>(maxEntries = 1000),
        )

        // Insert 6 entries - should evict since override sets max to 5
        repeat(6) { cache.put("k$it", "v$it") }
        assertEquals(5, cache.size())
    }

    @Test
    fun regexAndPatternsBlocksSizeTheirManagedCaches() {
        val manager = createManager(
            CacheConfig(regex = RegexCacheConfig(maxEntries = 2), patterns = PatternCacheConfig(maxEntries = 3)),
        )
        val regex = manager.getOrCreate(RegexCacheConfig.CACHE_NAME, CacheSpec<String, String>(maxEntries = 1000))
        val patterns = manager.getOrCreate(PatternCacheConfig.CACHE_NAME, CacheSpec<String, String>(maxEntries = 1000))

        repeat(5) {
            regex.put("k$it", "v$it")
            patterns.put("k$it", "v$it")
        }
        assertEquals(2, regex.size())
        assertEquals(3, patterns.size())
    }

    @Test
    fun memoryOverrideWinsOverDedicatedCacheBlock() {
        val manager = createManager(
            CacheConfig(
                regex = RegexCacheConfig(maxEntries = 2),
                memory = MemoryCacheConfig(overrides = mapOf(RegexCacheConfig.CACHE_NAME to MemoryOverride(maxEntries = 4))),
            ),
        )
        val regex = manager.getOrCreate(RegexCacheConfig.CACHE_NAME, CacheSpec<String, String>(maxEntries = 1000))

        repeat(6) { regex.put("k$it", "v$it") }
        assertEquals(4, regex.size())
    }

    @Test
    fun disabledManagerStillCreatesCaches() =
        runTest {
            val manager = CacheManager.disabled()
            val cache = manager.getOrCreate(
                "test",
                CacheSpec<String, String>(maxEntries = 10),
            )
            cache.put("k", "v")
            assertEquals("v", cache.get("k"))
        }

    @Test
    fun getCasCacheReturnsSameInstanceForSameNamespace() {
        val manager = createManager()
        val cache1 = manager.getCasCache("responses")
        val cache2 = manager.getCasCache("responses")
        assertSame(cache1, cache2)
    }

    @Test
    fun getCasCacheReturnsDifferentInstancesForDifferentNamespaces() {
        val manager = createManager()
        val cache1 = manager.getCasCache("responses")
        val cache2 = manager.getCasCache("downloads")
        assertTrue(cache1 !== cache2)
    }

    @Test
    fun getCasCacheIsFunctional() {
        val manager = createManager()
        val cache = manager.getCasCache("test-ns")
        cache.put("key", "value".encodeToByteArray(), ttlMs = 60_000L)
        val result = cache.get("key")
        assertNotNull(result)
        assertEquals("value", result.decodeToString())
    }

    @Test
    fun shutdownClearsCasCaches() {
        val manager = createManager()
        manager.getCasCache("test-ns")
        manager.shutdown()
        // After shutdown, getCasCache creates a new instance
        val newCache = manager.getCasCache("test-ns")
        assertNotNull(newCache)
    }

    @Test
    fun cacheConfigIsExposed() {
        val config = CacheConfig(dir = "/custom/path")
        val manager = createManager(config)
        assertEquals("/custom/path", manager.cacheConfig.dir)
    }

    @Test
    fun fileTierIsWrittenButNotReadWhenReadFilesIsOff() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("karandash-test-cache")

            fun cache(readFiles: Boolean) =
                CacheManager(cacheDir = dir, readFiles = readFiles).getPersistentStringCache("persisted", 10, 1.hours)

            cache(readFiles = false).put("k", "v")

            assertNull(cache(readFiles = false).get("k"))
            assertEquals("v", cache(readFiles = true).get("k"))
        }

    @Test
    fun enabledPropertyReflectsConfig() {
        val enabled = CacheManager(
            config = CacheConfig(enabled = true),
            cacheDir = TestFiles.createTestTempDirectory("test"),
        )
        assertEquals(true, enabled.enabled)

        val disabled = CacheManager(
            config = CacheConfig(enabled = false),
            cacheDir = TestFiles.createTestTempDirectory("test"),
        )
        assertEquals(false, disabled.enabled)
    }
}
