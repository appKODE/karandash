package io.karandash.utils

import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSpec
import io.karandash.cache.GlobCacheConfig
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `cache.glob.maxEntries` sizes the process-wide matcher once the run's cache manager is installed. */
class GlobMatcherManagedCacheTest {
    @Test
    fun `useManaged sizes the static matcher from cache glob maxEntries`() {
        val manager = CacheManager(
            config = CacheConfig(glob = GlobCacheConfig(maxEntries = 2)),
            cacheDir = TestFiles.createTestTempDirectory("karandash-test-cache"),
        )
        GlobMatcher.useManaged(manager)

        listOf("*.kt", "*.java", "*.md", "src/**").forEach { GlobMatcher.matches("a.kt", it) }
        assertTrue(GlobMatcher.matches("src/a/b.kt", "src/**"))

        val cache = manager.getOrCreate(GlobMatcher.CACHE_NAME, CacheSpec<String, Regex>(maxEntries = 1000))
        assertEquals(2, cache.size())
    }
}
