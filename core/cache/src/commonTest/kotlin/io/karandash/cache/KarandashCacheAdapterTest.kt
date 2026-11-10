package io.karandash.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KarandashCacheAdapterTest {
    private fun createAdapter(): KarandashCacheAdapter {
        val tiered = TieredCache<String, String>(
            name = "test",
            maxEntries = 100,
            tier = CacheTier.MEMORY_ONLY,
        )
        return KarandashCacheAdapter(tiered)
    }

    @Test
    fun getOrPutWithDependenciesReturnsCachedOnSameDeps() =
        runTest {
            val adapter = createAdapter()
            var computeCount = 0

            val result1 = adapter.getOrPut("rule1", mapOf("title" to "Fix")) {
                computeCount++
                "findings-json"
            }
            val result2 = adapter.getOrPut("rule1", mapOf("title" to "Fix")) {
                computeCount++
                "should-not-be-used"
            }

            assertEquals("findings-json", result1)
            assertEquals("findings-json", result2)
            assertEquals(1, computeCount)
        }

    @Test
    fun getOrPutWithDependenciesRecomputesOnChangedDeps() =
        runTest {
            val adapter = createAdapter()
            var computeCount = 0

            val result1 = adapter.getOrPut("rule1", mapOf("title" to "Fix bug")) {
                computeCount++
                "old-findings"
            }
            val result2 = adapter.getOrPut("rule1", mapOf("title" to "Add feature")) {
                computeCount++
                "new-findings"
            }

            assertEquals("old-findings", result1)
            assertEquals("new-findings", result2)
            assertEquals(2, computeCount)
        }

    @Test
    fun getOrPutWithDependenciesHandlesEmptyDeps() =
        runTest {
            val adapter = createAdapter()
            var computeCount = 0

            val result1 = adapter.getOrPut("rule1", emptyMap()) {
                computeCount++
                "value"
            }
            val result2 = adapter.getOrPut("rule1", emptyMap()) {
                computeCount++
                "other"
            }

            assertEquals("value", result1)
            assertEquals("value", result2)
            assertEquals(1, computeCount)
        }

    @Test
    fun invalidateRemovesDependencyHashToo() =
        runTest {
            val adapter = createAdapter()
            var computeCount = 0

            adapter.getOrPut("rule1", mapOf("title" to "Fix")) {
                computeCount++
                "cached"
            }

            adapter.invalidate("rule1")

            val result = adapter.getOrPut("rule1", mapOf("title" to "Fix")) {
                computeCount++
                "recomputed"
            }

            assertEquals("recomputed", result)
            assertEquals(2, computeCount)
        }

    @Test
    fun differentKeysAreCachedIndependently() =
        runTest {
            val adapter = createAdapter()
            val deps = mapOf("title" to "Fix")

            val result1 = adapter.getOrPut("rule-a", deps) { "findings-a" }
            val result2 = adapter.getOrPut("rule-b", deps) { "findings-b" }

            assertEquals("findings-a", result1)
            assertEquals("findings-b", result2)
        }

    @Test
    fun clearRemovesAllIncludingDependencyHashes() =
        runTest {
            val adapter = createAdapter()
            var computeCount = 0

            adapter.getOrPut("rule1", mapOf("title" to "Fix")) {
                computeCount++
                "cached"
            }

            adapter.clear()

            val result = adapter.getOrPut("rule1", mapOf("title" to "Fix")) {
                computeCount++
                "recomputed"
            }

            assertEquals("recomputed", result)
            assertEquals(2, computeCount)
        }
}
