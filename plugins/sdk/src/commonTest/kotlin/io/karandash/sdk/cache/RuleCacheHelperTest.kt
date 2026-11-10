package io.karandash.sdk.cache

import io.karandash.api.cache.KarandashCache
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleCacheHelperTest {
    /** Minimal in-memory [KarandashCache] for test isolation. */
    private class InMemoryCache : KarandashCache {
        private val store = mutableMapOf<String, String>()

        override suspend fun get(key: String): String? = store[key]

        override suspend fun put(
            key: String,
            value: String,
            ttlMs: Long
        ) {
            store[key] = value
        }

        override suspend fun getOrPut(
            key: String,
            compute: suspend () -> String
        ): String {
            store[key]?.let { return it }
            val value = compute()
            store[key] = value
            return value
        }

        override suspend fun getOrPut(
            key: String,
            dependencies: Map<String, String>,
            compute: suspend () -> String,
        ): String {
            // Simple dep-hash implementation for testing
            val depKey = "$key:dh"
            val depHash = dependencies.entries
                .sortedBy { it.key }
                .joinToString("|") { "${it.key}=${it.value}" }
            val storedHash = store[depKey]
            if (storedHash == depHash) {
                store[key]?.let { return it }
            }
            val value = compute()
            store[key] = value
            store[depKey] = depHash
            return value
        }

        override suspend fun invalidate(key: String) {
            store.remove(key)
            store.remove("$key:dh")
        }

        override suspend fun clear() {
            store.clear()
        }

        override fun size(): Int = store.size
    }

    private fun finding(msg: String): Finding =
        Finding(
            severity = Severity.WARNING,
            message = msg,
            rule = "test-rule",
        )

    @Test
    fun returnsCachedFindingsWhenDependenciesUnchanged() =
        runTest {
            val cache = InMemoryCache()
            var computeCount = 0

            val findings1 = RuleCacheHelper.cachedValidate(
                cache = cache,
                ruleId = "title-format",
                dependencies = mapOf("title" to "Fix bug"),
            ) {
                computeCount++
                listOf(finding("bad title"))
            }

            val findings2 = RuleCacheHelper.cachedValidate(
                cache = cache,
                ruleId = "title-format",
                dependencies = mapOf("title" to "Fix bug"),
            ) {
                computeCount++
                listOf(finding("should not appear"))
            }

            assertEquals(1, computeCount)
            assertEquals(1, findings1.size)
            assertEquals("bad title", findings1[0].message)
            assertEquals(findings1, findings2)
        }

    @Test
    fun recomputesWhenDependenciesChange() =
        runTest {
            val cache = InMemoryCache()
            var computeCount = 0

            val findings1 = RuleCacheHelper.cachedValidate(
                cache = cache,
                ruleId = "title-format",
                dependencies = mapOf("title" to "Fix bug"),
            ) {
                computeCount++
                listOf(finding("old finding"))
            }

            val findings2 = RuleCacheHelper.cachedValidate(
                cache = cache,
                ruleId = "title-format",
                dependencies = mapOf("title" to "Add feature"),
            ) {
                computeCount++
                listOf(finding("new finding"))
            }

            assertEquals(2, computeCount)
            assertEquals("old finding", findings1[0].message)
            assertEquals("new finding", findings2[0].message)
        }

    @Test
    fun returnsDirectResultWhenCacheIsNull() =
        runTest {
            var computeCount = 0

            val findings = RuleCacheHelper.cachedValidate(
                cache = null,
                ruleId = "title-format",
                dependencies = mapOf("title" to "Fix bug"),
            ) {
                computeCount++
                listOf(finding("direct"))
            }

            assertEquals(1, computeCount)
            assertEquals("direct", findings[0].message)
        }

    @Test
    fun handlesEmptyFindingsList() =
        runTest {
            val cache = InMemoryCache()

            val findings = RuleCacheHelper.cachedValidate(
                cache = cache,
                ruleId = "clean-rule",
                dependencies = mapOf("title" to "Good title"),
            ) {
                emptyList()
            }

            assertEquals(0, findings.size)

            // Second call should also return empty from cache
            var computeCount = 0
            val findings2 = RuleCacheHelper.cachedValidate(
                cache = cache,
                ruleId = "clean-rule",
                dependencies = mapOf("title" to "Good title"),
            ) {
                computeCount++
                listOf(finding("should not compute"))
            }

            assertEquals(0, computeCount)
            assertEquals(0, findings2.size)
        }
}
