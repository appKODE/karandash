package io.karandash.utils

import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** a CODEOWNERS pattern and a plain glob must not share a cache entry. */
class GlobMatcherCacheKeyTest {
    @Test
    fun `plain glob named like a codeowners cache key is not shadowed`() {
        val matcher = GlobMatcher(TieredCache("core2-glob", maxEntries = 100, tier = CacheTier.MEMORY_ONLY))

        assertTrue(matcher.matchCodeowners("x/docs/a.md", "docs/"))

        assertFalse(matcher.matchPath("x/docs/a.md", "codeowners:docs/"))
        assertTrue(matcher.matchPath("codeowners:docs/", "codeowners:docs/"))
    }
}
