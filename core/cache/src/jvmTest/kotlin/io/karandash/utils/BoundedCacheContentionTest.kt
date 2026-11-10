package io.karandash.utils

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM-only: needs real threads to contend on the cache lock.
 */
class BoundedCacheContentionTest {
    private fun <T> underContention(
        cache: BoundedCache<Int, Int>,
        body: () -> T
    ): T {
        val running = AtomicBoolean(true)
        val hammers = List(4) {
            thread {
                while (running.get()) {
                    cache.get(-1)
                    cache.size()
                }
            }
        }
        try {
            return body()
        } finally {
            running.set(false)
            hammers.forEach { it.join() }
        }
    }

    @Test
    fun `invalidate is never skipped under contention`() {
        val cache = BoundedCache<Int, Int>(maxEntries = 1000)
        val survived = underContention(cache) {
            var count = 0
            repeat(20_000) { i ->
                val key = i % 100
                cache.put(key, i)
                cache.invalidate(key)
                if (cache.get(key) != null) count++
            }
            count
        }
        assertEquals(0, survived, "invalidate was skipped $survived times")
    }

    @Test
    fun `clear and snapshot are never skipped under contention`() {
        val cache = BoundedCache<Int, Int>(maxEntries = 1000)
        val bad = underContention(cache) {
            var count = 0
            repeat(20_000) { i ->
                cache.put(1, i)
                if (cache.snapshot().isEmpty()) count++
                cache.clear()
                if (cache.size() != 0) count++
            }
            count
        }
        assertEquals(0, bad, "clear or snapshot lost $bad times")
    }
}
