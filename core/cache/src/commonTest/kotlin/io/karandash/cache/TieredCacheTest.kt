package io.karandash.cache

import io.karandash.cache.cas.CasCache
import io.karandash.logger.Logger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TieredCacheTest {
    @Test
    fun fileTierWriteFailureIsLoggedAndKeepsTheMemoryEntry() =
        runTest {
            // the cache dir is a regular file, so every CAS write fails with IOException
            val blocker = kotlinx.io.files.Path(TestFiles.createTestTempDirectory("karandash-tiered-put"), "blocker")
            SystemFileSystem.sink(blocker).buffered().use { it.writeString("x") }
            val warnings = mutableListOf<String>()
            val logger = object : Logger {
                override fun debug(message: String) = Unit

                override fun info(message: String) = Unit

                override fun warn(message: String) {
                    warnings.add(message)
                }

                override fun error(message: String) = Unit
            }
            val serializer = object : CacheSerializer<String, String> {
                override fun serializeKey(key: String) = key

                override fun serializeValue(value: String) = value

                override fun deserializeValue(data: String) = data
            }
            try {
                val cache = TieredCache(
                    name = "rule-results",
                    maxEntries = 10,
                    tier = CacheTier.MEMORY_AND_FILE,
                    serializer = serializer,
                    casCache = CasCache(baseDir = blocker, namespace = "state"),
                    logger = logger,
                )
                cache.put("k", "v")
                assertEquals("v", cache.get("k"))
                assertTrue(warnings.single().contains("file-tier write failed"), warnings.toString())
            } finally {
                SystemFileSystem.delete(blocker, mustExist = false)
            }
        }

    @Test
    fun getReturnsNullForMissingKey() =
        runTest {
            val cache = TieredCache<String, String>(
                name = "test",
                maxEntries = 10,
                tier = CacheTier.MEMORY_ONLY,
            )
            assertNull(cache.get("missing"))
        }

    @Test
    fun putAndGetReturnsValue() =
        runTest {
            val cache = TieredCache<String, String>(
                name = "test",
                maxEntries = 10,
                tier = CacheTier.MEMORY_ONLY,
            )
            cache.put("key1", "value1")
            assertEquals("value1", cache.get("key1"))
        }

    @Test
    fun getOrComputeReturnsExistingValue() =
        runTest {
            val cache = TieredCache<String, Int>(
                name = "test",
                maxEntries = 10,
                tier = CacheTier.MEMORY_ONLY,
            )
            cache.put("x", 42)
            var computed = false
            val result = cache.getOrCompute("x") {
                computed = true
                99
            }
            assertEquals(42, result)
            assertEquals(false, computed)
        }

    @Test
    fun getOrComputeCallsComputeOnMiss() =
        runTest {
            val cache = TieredCache<String, Int>(
                name = "test",
                maxEntries = 10,
                tier = CacheTier.MEMORY_ONLY,
            )
            val result = cache.getOrCompute("x") { 99 }
            assertEquals(99, result)
            assertEquals(99, cache.get("x"))
        }

    @Test
    fun invalidateRemovesEntry() =
        runTest {
            val cache = TieredCache<String, String>(
                name = "test",
                maxEntries = 10,
                tier = CacheTier.MEMORY_ONLY,
            )
            cache.put("k", "v")
            cache.invalidate("k")
            assertNull(cache.get("k"))
        }

    @Test
    fun clearRemovesAllEntries() =
        runTest {
            val cache = TieredCache<String, String>(
                name = "test",
                maxEntries = 10,
                tier = CacheTier.MEMORY_ONLY,
            )
            cache.put("a", "1")
            cache.put("b", "2")
            assertEquals(2, cache.size())
            cache.clear()
            assertEquals(0, cache.size())
            assertNull(cache.get("a"))
        }

    @Test
    fun evictsWhenOverCapacity() {
        val cache = TieredCache<String, String>(
            name = "test",
            maxEntries = 3,
            tier = CacheTier.MEMORY_ONLY,
        )
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        cache.put("d", "4")
        assertEquals(3, cache.size())
        val stats = cache.stats()
        assertEquals(1L, stats.evictions)
    }

    @Test
    fun statsTrackHitsAndMisses() =
        runTest {
            val cache = TieredCache<String, String>(
                name = "test",
                maxEntries = 10,
                tier = CacheTier.MEMORY_ONLY,
            )
            cache.put("k", "v")
            cache.get("k") // hit
            cache.get("missing") // miss

            val stats = cache.stats()
            assertEquals(1L, stats.hits)
            assertEquals(1L, stats.misses)
            assertEquals(1, stats.size)
        }

    @Test
    fun memoryAndFileRequiresSerializer() {
        assertFailsWith<IllegalArgumentException> {
            TieredCache<String, String>(
                name = "test",
                maxEntries = 10,
                tier = CacheTier.MEMORY_AND_FILE,
                serializer = null,
                casCache = null,
            )
        }
    }

    @Test
    fun memoryAndFileRequiresCasCache() {
        val serializer = object : CacheSerializer<String, String> {
            override fun serializeKey(key: String) = key

            override fun serializeValue(value: String) = value

            override fun deserializeValue(data: String) = data
        }
        assertFailsWith<IllegalArgumentException> {
            TieredCache<String, String>(
                name = "test",
                maxEntries = 10,
                tier = CacheTier.MEMORY_AND_FILE,
                serializer = serializer,
                casCache = null,
            )
        }
    }

    @Test
    fun nameIsExposed() {
        val cache = TieredCache<String, String>(
            name = "my-cache",
            maxEntries = 10,
        )
        assertEquals("my-cache", cache.name)
    }

    @Test
    fun multipleGetOrComputeWithSameKeyReturnsSameValue() =
        runTest {
            val cache = TieredCache<String, Int>(
                name = "test",
                maxEntries = 10,
            )
            var callCount = 0
            repeat(5) {
                cache.getOrCompute("key") {
                    callCount++
                    42
                }
            }
            assertEquals(1, callCount)
            assertEquals(42, cache.get("key"))
        }
}
