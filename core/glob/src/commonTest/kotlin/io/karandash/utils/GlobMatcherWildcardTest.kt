package io.karandash.utils

import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cache.GlobCacheConfig
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [GlobMatcher.matchWildcard]: the label/text glob the rules use, cached in the `cache.glob`-sized cache. */
class GlobMatcherWildcardTest {
    private fun manager(maxEntries: Int) =
        CacheManager(
            config = CacheConfig(glob = GlobCacheConfig(maxEntries = maxEntries)),
            cacheDir = TestFiles.createTestTempDirectory("karandash-wildcard-cache"),
        )

    @AfterTest
    fun restoreDefault() = GlobMatcher.useManaged(manager(GlobCacheConfig().maxEntries))

    @Test
    fun `exact patterns compare whole strings and honour ignoreCase`() {
        assertTrue(GlobMatcher.wildcardMatches("hello", "hello"))
        assertFalse(GlobMatcher.wildcardMatches("hello", "world"))
        assertTrue(GlobMatcher.wildcardMatches("Hello", "hello", ignoreCase = true))
        assertFalse(GlobMatcher.wildcardMatches("Hello", "hello"))
        assertFalse(GlobMatcher.wildcardMatches("fileTtxt", "file.txt"))
        assertTrue(GlobMatcher.Default.matchWildcard("feat/login", "feat/*"))
        assertFalse(GlobMatcher.Default.matchWildcard("Hello", "hello"))
    }

    @Test
    fun `star and question mark cross slash`() {
        assertTrue(GlobMatcher.wildcardMatches("scope/team/backend", "scope/*"))
        assertTrue(GlobMatcher.wildcardMatches("a/b", "a?b"))
        assertFalse(GlobMatcher.wildcardMatches("xscope/a", "scope/*"))
        assertTrue(GlobMatcher.wildcardMatches("FEAT/login", "feat/*", ignoreCase = true))
        assertFalse(GlobMatcher.wildcardMatches("FEAT/login", "feat/*"))
    }

    @Test
    fun `cache glob maxEntries bounds the wildcard globs and their stats`() {
        val manager = manager(maxEntries = 2)
        GlobMatcher.useManaged(manager)

        listOf("a-*", "b-*", "c-*").forEach { GlobMatcher.wildcardMatches("x", it) }

        val stats = checkNotNull(manager.stats(GlobMatcher.CACHE_NAME))
        assertEquals(2, stats.size)
        assertTrue(stats.evictions > 0, "$stats")
    }

    @Test
    fun `a wildcard glob and a path glob with the same text stay apart`() {
        GlobMatcher.useManaged(manager(maxEntries = 10))

        assertTrue(GlobMatcher.wildcardMatches("src/a/b", "src/*"))
        assertFalse(GlobMatcher.matches("src/a/b", "src/*"))
        assertTrue(GlobMatcher.wildcardMatches("src/a/b", "src/*"))
    }

    @Test
    fun `100 coroutines compiling globs at once all match`() =
        runTest {
            GlobMatcher.useManaged(manager(maxEntries = 16))

            val results = withContext(Dispatchers.Default) {
                (0 until 100).map { i -> async { GlobMatcher.wildcardMatches("label-${i % 40}-x", "label-${i % 40}-*") } }.awaitAll()
            }

            assertTrue(results.all { it })
        }
}
