package io.karandash.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class BoundedCacheTest {
    @Test
    fun getReturnsNullForAbsentKey() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        assertNull(cache.get("missing"))
    }

    @Test
    fun putAndGetRoundTrip() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("key", "value")
        assertEquals("value", cache.get("key"))
    }

    @Test
    fun getOrComputeReturnsExistingValue() {
        val cache = BoundedCache<String, Int>(maxEntries = 10)
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
    fun getOrComputeCallsComputeOnMiss() {
        val cache = BoundedCache<String, Int>(maxEntries = 10)
        val result = cache.getOrCompute("x") { 99 }
        assertEquals(99, result)
        assertEquals(99, cache.get("x"))
    }

    @Test
    fun evictsFromProbationaryWhenFull() {
        val cache = BoundedCache<String, String>(maxEntries = 5)
        // Fill cache
        for (i in 1..5) cache.put("k$i", "v$i")
        // Adding one more should evict the oldest probationary entry
        cache.put("k6", "v6")
        assertEquals(5, cache.size())
        // k1 should have been evicted (oldest in probationary)
        assertNull(cache.get("k1"))
        assertNotNull(cache.get("k6"))
    }

    @Test
    fun promotesToProtectedOnSecondAccess() {
        val cache = BoundedCache<String, String>(maxEntries = 5)
        cache.put("hot", "value")
        // First get promotes from probationary to protected
        assertEquals("value", cache.get("hot"))

        // Fill remaining capacity to force evictions from probationary
        for (i in 1..10) cache.put("cold$i", "v$i")

        // "hot" should survive because it's in the protected segment
        assertEquals("value", cache.get("hot"))
    }

    @Test
    fun ttlExpiresEntries() {
        var now = Instant.fromEpochSeconds(1000)
        val cache = BoundedCache<String, String>(
            maxEntries = 10,
            ttl = 5.minutes,
            clock = { now },
        )
        cache.put("key", "value")
        assertEquals("value", cache.get("key"))

        // Advance past TTL
        now = Instant.fromEpochSeconds(1000 + 301)
        assertNull(cache.get("key"))
    }

    @Test
    fun ttlDoesNotExpireBeforeDuration() {
        var now = Instant.fromEpochSeconds(1000)
        val cache = BoundedCache<String, String>(
            maxEntries = 10,
            ttl = 5.minutes,
            clock = { now },
        )
        cache.put("key", "value")

        // Advance but stay within TTL
        now = Instant.fromEpochSeconds(1000 + 299)
        assertEquals("value", cache.get("key"))
    }

    @Test
    fun invalidateRemovesEntry() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("key", "value")
        cache.invalidate("key")
        assertNull(cache.get("key"))
        assertEquals(0, cache.size())
    }

    @Test
    fun invalidateRemovesFromProtected() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("key", "value")
        cache.get("key") // promote to protected
        cache.invalidate("key")
        assertNull(cache.get("key"))
    }

    @Test
    fun clearResetsEverything() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.get("a") // promote
        cache.clear()
        assertEquals(0, cache.size())

        // Stats should be zeroed immediately after clear
        val stats = cache.stats()
        assertEquals(0L, stats.hits)
        assertEquals(0L, stats.misses)
        assertEquals(0L, stats.evictions)

        // Entries are gone
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }

    @Test
    fun statsTracksHitsAndMisses() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("key", "value")
        cache.get("key") // hit
        cache.get("key") // hit
        cache.get("missing") // miss

        val stats = cache.stats()
        assertEquals(2L, stats.hits)
        assertEquals(1L, stats.misses)
    }

    @Test
    fun statsTracksEvictions() {
        val cache = BoundedCache<String, String>(maxEntries = 3)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        cache.put("d", "4") // evicts "a"

        val stats = cache.stats()
        assertEquals(stats.evictions > 0, true)
    }

    @Test
    fun snapshotReturnsAllNonExpiredEntries() {
        var now = Instant.fromEpochSeconds(1000)
        val cache = BoundedCache<String, String>(
            maxEntries = 10,
            ttl = 60.seconds,
            clock = { now },
        )
        cache.put("a", "1")
        cache.put("b", "2")

        now = Instant.fromEpochSeconds(1030)
        cache.put("c", "3")

        // Advance past TTL for first two entries
        now = Instant.fromEpochSeconds(1070)
        val snap = cache.snapshot()
        assertEquals(1, snap.size)
        assertEquals("3", snap["c"])
    }

    @Test
    fun snapshotIsACopy() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("key", "value")
        val snap = cache.snapshot()
        cache.put("key", "updated")
        assertEquals("value", snap["key"])
    }

    @Test
    fun maxEntriesOneWorks() {
        val cache = BoundedCache<String, String>(maxEntries = 1)
        cache.put("a", "1")
        assertEquals("1", cache.get("a"))
        cache.put("b", "2")
        assertEquals(1, cache.size())
        assertEquals("2", cache.get("b"))
    }

    @Test
    fun rejectsZeroMaxEntries() {
        assertFailsWith<IllegalArgumentException> {
            BoundedCache<String, String>(maxEntries = 0)
        }
    }

    @Test
    fun rejectsNegativeMaxEntries() {
        assertFailsWith<IllegalArgumentException> {
            BoundedCache<String, String>(maxEntries = -1)
        }
    }

    @Test
    fun putOverwritesExistingValue() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("key", "old")
        cache.put("key", "new")
        assertEquals("new", cache.get("key"))
        assertEquals(1, cache.size())
    }

    @Test
    fun putOverwritesInProtectedSegment() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("key", "old")
        cache.get("key") // promote to protected
        cache.put("key", "new")
        assertEquals("new", cache.get("key"))
    }

    @Test
    fun protectedSegmentDemotesToProbationary() {
        // With maxEntries=5, protected capacity = 4, probationary capacity = 1
        val cache = BoundedCache<String, String>(maxEntries = 5)

        // Put entries and promote them all to protected
        for (i in 1..4) {
            cache.put("p$i", "v$i")
            cache.get("p$i") // promote
        }

        // Put a new entry and promote it - should demote p1 from protected to probationary
        cache.put("p5", "v5")
        cache.get("p5") // promote

        // All entries should still be accessible
        assertEquals(5, cache.size())
    }

    @Test
    fun hitRateComputedCorrectly() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("key", "value")

        // 3 hits, 2 misses
        cache.get("key")
        cache.get("key")
        cache.get("key")
        cache.get("absent1")
        cache.get("absent2")

        val stats = cache.stats()
        assertEquals(0.6, stats.hitRate, 0.001)
    }

    @Test
    fun hitRateIsZeroWithNoLookups() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        assertEquals(0.0, cache.stats().hitRate)
    }

    @Test
    fun importAllPopulatesCache() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.importAll(mapOf("a" to "1", "b" to "2", "c" to "3"))
        assertEquals(3, cache.size())
        assertEquals("1", cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun importAllRespectsCapacity() {
        val cache = BoundedCache<String, String>(maxEntries = 2)
        cache.importAll(mapOf("a" to "1", "b" to "2", "c" to "3"))
        assertEquals(2, cache.size())
        // Last two entries should survive
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun importAllPreservesExistingEntries() {
        val cache = BoundedCache<String, String>(maxEntries = 10)
        cache.put("existing", "value")
        cache.importAll(mapOf("a" to "1"))
        assertEquals(2, cache.size())
        assertEquals("value", cache.get("existing"))
        assertEquals("1", cache.get("a"))
    }

    @Test
    fun nullTtlMeansNoExpiration() {
        var now = Instant.fromEpochSeconds(0)
        val cache = BoundedCache<String, String>(
            maxEntries = 10,
            ttl = null,
            clock = { now },
        )
        cache.put("key", "value")
        now = Instant.fromEpochSeconds(Long.MAX_VALUE / 2)
        assertEquals("value", cache.get("key"))
    }
}
